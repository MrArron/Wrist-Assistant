package com.mrarron.wristclaude.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class ChatMessage(val role: String, val content: String)

data class HttpResult(val status: Int, val json: JsonObject?, val netError: Boolean)

/** Sends one Messages API request. Swappable for tests. */
fun interface ClaudeTransport {
    suspend fun post(apiKey: String, body: JsonObject, timeoutMs: Long): HttpResult
}

/** Result of running one client-side tool. [note] is used as the answer if Claude returns no text. */
data class ToolOutcome(val content: String, val isError: Boolean = false, val note: String? = null) {
    companion object {
        fun error(msg: String) = ToolOutcome(msg, isError = true)
    }
}

interface ToolExecutor {
    /** Tools that can be offered right now (permissions granted). */
    fun availability(): ToolAvailability

    /** Short background on what the user is working on (from the context doc), or null. */
    fun background(): String? = null

    /** The phone's current city-level location, or null (no permission, off, or unknown). */
    suspend fun location(): Place? = null

    /** Run a tool. Time spent waiting for the user's watch confirmation is reported via [ToolContext.pauseClock]. */
    suspend fun execute(name: String, input: JsonObject, ctx: ToolContext): ToolOutcome
}

interface ToolContext {
    val now: ZonedDateTime
    /** True once untrusted content (web search results, notification contents) has entered this conversation. */
    val tainted: Boolean
    /** The conversation before the current question (for create_handoff). */
    val history: List<ChatMessage> get() = emptyList()
    suspend fun status(text: String)
    suspend fun reminder(unix: Long, text: String)
    /** Run [block] without counting its time against the 60 s ask budget (watch confirmations). */
    suspend fun <T> pauseClock(block: suspend () -> T): T
}

sealed class AskResult {
    data class Answer(val text: String, val tainted: Boolean = false) : AskResult()
    data class Error(val text: String) : AskResult()
}

object Msg {
    const val NO_KEY = "Add your API key in the Wrist Assistant phone app."
    const val UNAUTHORIZED = "API key rejected. Check it in settings."
    const val BUSY = "Claude is busy. Try again in a moment."
    const val NETWORK = "No connection to Claude. Check your phone's internet."
    const val EMPTY = "Claude sent no answer. Try again."
    const val TOO_SLOW = "That took too long. Try a simpler question."
}

class OkHttpTransport(private val client: OkHttpClient = defaultClient) : ClaudeTransport {
    companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(50, TimeUnit.SECONDS).build()
        }
        private val JSON_TYPE = "application/json".toMediaType()
    }

    override suspend fun post(apiKey: String, body: JsonObject, timeoutMs: Long): HttpResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API_URL)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(req)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resume(HttpResult(0, null, true))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val r = response.use {
                            val text = runCatching { it.body.string() }.getOrNull()
                            val json = text?.let { t -> runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() }
                            HttpResult(it.code, json, false)
                        }
                        if (cont.isActive) cont.resume(r)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HttpResult(0, null, true)
        }
    }
}

/**
 * Port of src/pkjs/claude.js ask(): system prompt, tools, tool_use / pause_turn loop.
 * Max 6 rounds; the whole ask stays under 60 s (time spent waiting for watch confirmations excluded);
 * "Still working..." is sent for every extra round and every ~20 s while waiting on the API.
 */
class ClaudeClient(
    private val transport: ClaudeTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nowProvider: () -> ZonedDateTime = ZonedDateTime::now,
    private val usage: UsageMeter = UsageMeter.NONE,
) {
    companion object {
        const val MAX_TOKENS = 1024
        const val MAX_ROUNDS = 6
        const val OVERALL_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 45_000L
        const val KEEPALIVE_MS = 20_000L
        const val MAX_HISTORY = 6

        fun errorText(status: Int, json: JsonObject?): String {
            if (status == 401 || status == 403) return Msg.UNAUTHORIZED
            if (status == 429 || status == 529) return Msg.BUSY
            val m = ((json?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                ?.let { TextUtil.sanitize(it) }.orEmpty()
            return TextUtil.truncateBytes("Error $status" + (if (m.isNotEmpty()) ": $m" else ""), TextUtil.MAX_ERROR_BYTES)
        }

        fun collectText(content: JsonArray): String = TextUtil.sanitize(
            content.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull null
                if ((o["type"] as? JsonPrimitive)?.content == "text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
            }.joinToString("")
        )
    }

    interface Callbacks {
        suspend fun onStatus(text: String)
        suspend fun onReminder(unix: Long, text: String)
    }

    suspend fun ask(
        settings: AppSettings,
        apiKey: String?,
        history: List<ChatMessage>,
        userText: String,
        tools: ToolExecutor,
        cb: Callbacks,
        initiallyTainted: Boolean = false,
    ): AskResult = coroutineScope {
        if (apiKey.isNullOrEmpty()) return@coroutineScope AskResult.Error(Msg.NO_KEY)
        if (usage.overLimit()) return@coroutineScope AskResult.Error(Usage.LIMIT_REACHED)

        val conversationBefore = history.filter { it.role == "user" || it.role == "assistant" }
        val messages = ArrayList<JsonElement>()
        history.filter { it.role == "user" || it.role == "assistant" }.forEach {
            messages += buildJsonObject { put("role", it.role); put("content", it.content) }
        }
        messages += buildJsonObject { put("role", "user"); put("content", userText) }

        val now = nowProvider()
        val avail = tools.availability()
        val place = tools.location()
        val system = Prompt.buildSystemPrompt(settings, now, avail, tools.background(), place)
        val toolDefs = Prompt.buildTools(settings, avail, place)
        val offered = Prompt.toolNames(toolDefs)

        var pausedMs = 0L
        val startedAt = clock()
        fun elapsed() = clock() - startedAt - pausedMs
        var lastNote: String? = null
        var taint = initiallyTainted

        /** After a side-effecting tool succeeded, report what was done instead of an error (avoids a re-ask doing it twice). */
        fun fail(msg: String): AskResult = lastNote?.let { AskResult.Answer(it, taint) } ?: AskResult.Error(msg)

        val ctx = object : ToolContext {
            override val now: ZonedDateTime get() = nowProvider()
            override val tainted: Boolean get() = taint
            override val history: List<ChatMessage> get() = conversationBefore
            override suspend fun status(text: String) = cb.onStatus(text)
            override suspend fun reminder(unix: Long, text: String) = cb.onReminder(unix, text)
            override suspend fun <T> pauseClock(block: suspend () -> T): T {
                val t0 = clock()
                try {
                    return block()
                } finally {
                    pausedMs += clock() - t0
                }
            }
        }

        fun finishWith(content: JsonArray): AskResult {
            var answer = collectText(content)
            if (answer.isEmpty() && lastNote != null) answer = lastNote!!
            return if (answer.isEmpty()) AskResult.Error(Msg.EMPTY) else AskResult.Answer(answer, taint)
        }

        cb.onStatus("Thinking...")
        var round = 0
        while (true) {
            round++
            val remaining = OVERALL_MS - elapsed()
            if (remaining < 3_000) return@coroutineScope fail(Msg.TOO_SLOW)
            if (round > 1) cb.onStatus("Still working...")
            val body = buildJsonObject {
                put("model", settings.model)
                put("max_tokens", MAX_TOKENS)
                put("system", system)
                put("messages", JsonArray(messages.toList()))
                put("tools", toolDefs)
            }
            val keepAlive = launch {
                while (true) {
                    delay(KEEPALIVE_MS)
                    cb.onStatus("Still working...")
                }
            }
            val res = try {
                withTimeoutOrNull(remaining) { transport.post(apiKey, body, minOf(REQUEST_TIMEOUT_MS, remaining)) }
            } finally {
                keepAlive.cancel()
            }
            if (res == null) return@coroutineScope fail(Msg.TOO_SLOW)
            if (res.netError) return@coroutineScope fail(Msg.NETWORK)
            if (res.status != 200 || res.json == null) return@coroutineScope fail(errorText(res.status, res.json))
            val data = res.json
            (data["usage"] as? JsonObject)?.let { usage.record(settings.model, it, newAsk = round == 1) }
            val content = data["content"] as? JsonArray ?: JsonArray(emptyList())
            val types = content.mapNotNull { (it as? JsonObject)?.get("type")?.let { t -> (t as? JsonPrimitive)?.content } }
            if ("server_tool_use" in types) cb.onStatus("Searching the web...")
            // Web results are untrusted: anything Claude does after seeing them is gated
            if (types.any { it == "server_tool_use" || it.endsWith("_tool_result") }) taint = true
            when ((data["stop_reason"] as? JsonPrimitive)?.contentOrNull) {
                "tool_use" -> {
                    val results = buildJsonArray {
                        for (b in content) {
                            val o = b as? JsonObject ?: continue
                            if ((o["type"] as? JsonPrimitive)?.content != "tool_use") continue
                            val id = (o["id"] as? JsonPrimitive)?.content ?: continue
                            val name = (o["name"] as? JsonPrimitive)?.content ?: ""
                            val input = o["input"] as? JsonObject ?: JsonObject(emptyMap())
                            val outcome = if (name !in offered) {
                                ToolOutcome.error("Unknown tool.")
                            } else if (name == "set_reminder") {
                                runReminder(input, ctx)
                            } else {
                                try {
                                    tools.execute(name, input, ctx)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    ToolOutcome.error("The tool failed: ${e.javaClass.simpleName}")
                                }
                            }
                            if (outcome.note != null) lastNote = outcome.note
                            if (name in Prompt.UNTRUSTED_TOOLS && !outcome.isError) taint = true
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", id)
                                put("content", outcome.content)
                                if (outcome.isError) put("is_error", true)
                            })
                        }
                    }
                    if (results.isEmpty() || round >= MAX_ROUNDS) return@coroutineScope finishWith(content)
                    messages += buildJsonObject { put("role", "assistant"); put("content", content) }
                    messages += buildJsonObject { put("role", "user"); put("content", results) }
                }

                "pause_turn" -> {
                    if (round >= MAX_ROUNDS) return@coroutineScope finishWith(content)
                    messages += buildJsonObject { put("role", "assistant"); put("content", content) }
                }

                else -> return@coroutineScope finishWith(content)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        AskResult.Error(Msg.EMPTY)
    }

    private suspend fun runReminder(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val mins: Any? = (input["minutes_from_now"] as? JsonPrimitive)?.let { p ->
            if (p.isString) p.content else p.content.toDoubleOrNull()
        }
        val at = (input["at_local_time"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val now = ctx.now
        val t = TimeUtil.reminderTime(mins, at, now)
            ?: return ToolOutcome.error("Could not understand the time. Provide minutes_from_now or at_local_time as HH:MM.")
        val raw = (input["text"] as? JsonPrimitive)?.contentOrNull ?: "Reminder"
        val text = TextUtil.oneLineCapped(raw, 60).ifEmpty { "Reminder" }
        ctx.reminder(t, text)
        val f = TimeUtil.formatReminder(t, now)
        return ToolOutcome("Reminder set for $f", note = "Reminder set for $f.")
    }
}

/** Helpers for reading tool inputs. */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toLong()
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.lowercase()?.let {
    when (it) { "true" -> true; "false" -> false; else -> null }
}
fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

