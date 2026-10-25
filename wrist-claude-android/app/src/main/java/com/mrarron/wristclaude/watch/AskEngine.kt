package com.mrarron.wristclaude.watch

import com.mrarron.wristclaude.core.AppSettings
import com.mrarron.wristclaude.core.AskResult
import com.mrarron.wristclaude.core.ChatMessage
import com.mrarron.wristclaude.core.ClaudeClient
import com.mrarron.wristclaude.core.Msg
import com.mrarron.wristclaude.core.Notes
import com.mrarron.wristclaude.core.QuickTopics
import com.mrarron.wristclaude.core.TextUtil
import com.mrarron.wristclaude.core.ToolExecutor
import io.rebble.pebblekit2.common.model.PebbleDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Handles messages from the watch (port of src/pkjs/index.js for the companion route).
 * Pure Kotlin + interfaces so the protocol logic can be unit-tested without Android.
 */
class AskEngine(
    private val scope: CoroutineScope,
    private val link: WatchLink,
    private val confirmer: WatchConfirmer,
    private val client: ClaudeClient,
    private val env: Env,
    private val random: Random = Random.Default,
) {
    interface Env {
        fun settings(): AppSettings
        fun apiKey(): String?
        fun hasKey(): Boolean
        fun tools(): ToolExecutor
        fun speak(on: Boolean)
        fun onAnswer(answer: String)
        /** The watch app opened: retry queued notes, refresh the context doc. */
        fun onWatchOpen() {}
        /** This app's versionCode, sent in the hello so the watch can spot a mismatch; 0 = don't send. */
        fun appVersionCode(): Int = 0
        /** The watch app's version from its READY (null: a watch app older than 2.2.1). */
        fun onWatchVersion(code: Int?) {}
        /** Keep the process alive while [block] runs (foreground service). */
        suspend fun <T> keepAlive(block: suspend () -> T): T
    }

    companion object {
        const val MAX_HISTORY = ClaudeClient.MAX_HISTORY
        const val ASK_NEW = 0
        const val ASK_FOLLOWUP = 1
        const val ASK_QUICK = 2
        const val ASK_HANDOFF = 3
    }

    @Volatile private var conversation: List<ChatMessage> = emptyList()
    /** Untrusted content (web results, notifications) is in [conversation]; follow-ups stay gated. */
    @Volatile private var conversationTainted = false
    private val askId = AtomicInteger(0)
    @Volatile private var current: Job? = null
    /** {topic} quick prompt -> its last few answers, so they aren't repeated. */
    private val recentByPrompt = ConcurrentHashMap<String, List<String>>()

    /** The hello: COMPANION=1 plus key status, quick prompt names and this app's version. */
    fun helloDict(): PebbleDictionary {
        val base = listOf<Pair<UInt, Any>>(
            Keys.COMPANION to 1,
            Keys.READY to (if (env.hasKey()) 1 else 0),
            Keys.QUICK_NAMES to env.settings().quickNames(),
        )
        val version = env.appVersionCode()
        return dict(*(if (version > 0) base + (Keys.APP_VERSION to version) else base).toTypedArray())
    }

    fun sendHello() {
        scope.launch { link.enqueue(helloDict()) }
    }

    /** Returns true if the message was for us (and was handled). */
    fun onMessage(d: PebbleDictionary): Boolean {
        val route = d.int(Keys.ROUTE)
        val isAsk = d.containsKey(Keys.ASK_KIND) || d.containsKey(Keys.ASK_TEXT)
        val isSpeak = d.containsKey(Keys.SPEAK)
        val isConfirm = d.containsKey(Keys.CONFIRM_RESULT)
        if (isAsk || isSpeak || isConfirm) {
            if (route != 1) return false // meant for the Pebble-app JS
            if (isConfirm) {
                val id = d.int(Keys.CONFIRM_ID) ?: return true
                confirmer.onResult(id, d.int(Keys.CONFIRM_RESULT) == 1)
            }
            if (isSpeak) env.speak(d.int(Keys.SPEAK) == 1)
            if (isAsk) handleAsk(d)
            return true
        }
        if (d.containsKey(Keys.READY)) {
            env.onWatchVersion(d.int(Keys.APP_VERSION))
            sendHello()
            env.onWatchOpen()
            return true
        }
        return false
    }

    private fun handleAsk(d: PebbleDictionary) {
        val myId = askId.incrementAndGet()
        current?.cancel()
        current = scope.launch {
            link.clearPending()
            val settings = env.settings()
            val kind = d.int(Keys.ASK_KIND) ?: ASK_NEW
            val handoff = kind == ASK_HANDOFF
            if (handoff) {
                if (conversation.isEmpty()) {
                    sendError("Nothing to hand off yet. Ask something first."); return@launch
                }
                if (!env.tools().availability().notes) {
                    sendError("Hand off needs a Google account. Connect one in the Wrist Assistant phone app."); return@launch
                }
            }
            var topicPrompt: String? = null
            val userText = if (handoff) {
                val comment = d.text(Keys.ASK_TEXT).orEmpty().trim()
                Notes.HANDOFF_ASK + (if (comment.isNotEmpty()) " The user added: $comment" else "")
            } else if (kind == ASK_QUICK) {
                val q = settings.quickPrompts.getOrNull(d.int(Keys.QUICK_INDEX) ?: -1)
                if (q == null) {
                    sendError("Quick prompt not found. Check settings."); return@launch
                }
                if (QuickTopics.hasTopic(q.prompt)) {
                    topicPrompt = q.prompt
                    QuickTopics.expand(q.prompt, recentByPrompt[q.prompt].orEmpty(), random)
                } else {
                    q.prompt
                }
            } else {
                d.text(Keys.ASK_TEXT).orEmpty()
            }.trim()
            if (userText.isEmpty()) {
                sendError("Didn't catch that. Try again."); return@launch
            }
            val followUp = kind == ASK_FOLLOWUP || handoff
            val history = if (followUp) conversation.takeLast(MAX_HISTORY) else emptyList<ChatMessage>().also { conversation = it }
            val startTainted = followUp && conversationTainted

            val cb = object : ClaudeClient.Callbacks {
                override suspend fun onStatus(text: String) {
                    if (myId == askId.get()) link.enqueue(dict(Keys.STATUS to TextUtil.truncateBytes(text, TextUtil.MAX_ERROR_BYTES)))
                }

                override suspend fun onReminder(unix: Long, text: String) {
                    if (myId == askId.get()) {
                        link.enqueue(dict(Keys.REMINDER_TIME to unix.toInt(), Keys.REMINDER_TEXT to TextUtil.truncateBytes(text, 60)))
                    }
                }
            }
            val result = try {
                env.keepAlive { client.ask(settings, env.apiKey(), history, userText, env.tools(), cb, startTainted) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AskResult.Error(Msg.NETWORK)
            }
            if (myId != askId.get()) return@launch
            when (result) {
                is AskResult.Answer -> {
                    conversationTainted = result.tainted
                    // A handoff leaves the conversation as it was, so follow-ups continue from the real exchange.
                    if (!handoff) {
                        conversation = (history + ChatMessage("user", userText) + ChatMessage("assistant", result.text)).takeLast(MAX_HISTORY)
                    }
                    // Only clean answers go back into a later prompt.
                    topicPrompt?.let { p -> if (!result.tainted) recentByPrompt[p] = QuickTopics.remember(recentByPrompt[p].orEmpty(), result.text) }
                    env.onAnswer(result.text)
                    sendAnswer(result.text)
                }
                is AskResult.Error -> sendError(result.text)
            }
        }
    }

    private suspend fun sendError(msg: String) {
        link.enqueue(dict(Keys.ERROR to TextUtil.truncateBytes(msg, TextUtil.MAX_ERROR_BYTES)))
    }

    private suspend fun sendAnswer(answer: String) {
        val pieces = TextUtil.watchChunks(answer)
        if (pieces.isEmpty()) {
            sendError(Msg.EMPTY); return
        }
        pieces.forEachIndexed { i, p ->
            if (i == pieces.lastIndex) link.enqueue(dict(Keys.CHUNK to p, Keys.CHUNK_FINAL to 1))
            else link.enqueue(dict(Keys.CHUNK to p))
        }
    }
}
