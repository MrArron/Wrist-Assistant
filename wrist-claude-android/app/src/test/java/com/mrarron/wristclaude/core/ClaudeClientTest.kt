package com.mrarron.wristclaude.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ClaudeClientTest {
    private val now = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("America/New_York"))

    private class FakeTransport(private val replies: List<HttpResult>) : ClaudeTransport {
        val bodies = ArrayList<JsonObject>()
        val keys = ArrayList<String>()
        override suspend fun post(apiKey: String, body: JsonObject, timeoutMs: Long): HttpResult {
            keys += apiKey; bodies += body
            return replies[minOf(bodies.size - 1, replies.size - 1)]
        }
    }

    private class FakeTools(val avail: ToolAvailability = ToolAvailability()) : ToolExecutor {
        val calls = ArrayList<Pair<String, JsonObject>>()
        override fun availability() = avail
        override suspend fun execute(name: String, input: JsonObject, ctx: ToolContext): ToolOutcome {
            calls += name to input
            return ToolOutcome("Text draft ready.", note = "Draft ready on your phone.")
        }
    }

    private class Cb : ClaudeClient.Callbacks {
        val statuses = ArrayList<String>()
        val reminders = ArrayList<Pair<Long, String>>()
        override suspend fun onStatus(text: String) { statuses += text }
        override suspend fun onReminder(unix: Long, text: String) { reminders += unix to text }
    }

    private fun ok(json: String) = HttpResult(200, Json.parseToJsonElement(json).jsonObject, false)

    private fun client(t: ClaudeTransport) = ClaudeClient(t, clock = { 0L }, nowProvider = { now })

    @Test
    fun simpleAnswerIsSanitizedAndRequestIsCorrect() = runTest {
        val t = FakeTransport(listOf(ok("""{"stop_reason":"end_turn","content":[{"type":"text","text":"**Hi** “there”"}]}""")))
        val cb = Cb()
        val r = client(t).ask(AppSettings(city = "Springfield"), "sk-test", listOf(ChatMessage("user", "a"), ChatMessage("assistant", "b")), "q", FakeTools(), cb)
        assertEquals(AskResult.Answer("Hi \"there\""), r)
        assertEquals(listOf("Thinking..."), cb.statuses)
        val body = t.bodies.single()
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(3, body["messages"]!!.jsonArray.size)
        val system = body["system"]!!.jsonPrimitive.content
        assertTrue(system.contains("Current local date and time: Wed Oct 14 2026, 12:00 (UTC-04:00). Time zone: America/New_York."))
        assertTrue(system.contains("approval"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        val ws = tools.single { it["name"]?.jsonPrimitive?.content == "web_search" }
        assertEquals("web_search_20250305", ws["type"]!!.jsonPrimitive.content)
        assertEquals(3, ws["max_uses"]!!.jsonPrimitive.content.toInt())
        assertEquals("Springfield", ws["user_location"]!!.jsonObject["city"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsOfferedOnlyWhenPermitted() = runTest {
        val noPerms = Prompt.toolNames(Prompt.buildTools(AppSettings(webSearch = false), ToolAvailability()))
        assertEquals(setOf("set_reminder", "create_draft", "set_alarm", "set_timer"), noPerms)
        val all = Prompt.toolNames(
            Prompt.buildTools(AppSettings(), ToolAvailability(calendarRead = true, calendarWrite = true, notifications = true))
        )
        assertTrue(all.containsAll(listOf("get_calendar_events", "add_calendar_event", "get_recent_notifications", "reply_to_notification", "web_search")))
        val ws = Prompt.buildTools(AppSettings(city = ""), ToolAvailability()).last().jsonObject
        assertFalse(ws.containsKey("user_location"))
    }

    @Test
    fun toolLoopRunsReminderAndPhoneTools() = runTest {
        val t = FakeTransport(
            listOf(
                ok("""{"stop_reason":"tool_use","content":[
                    {"type":"tool_use","id":"t1","name":"set_reminder","input":{"minutes_from_now":20,"text":"Check the oven — now"}},
                    {"type":"tool_use","id":"t2","name":"create_draft","input":{"kind":"text","body":"hi"}},
                    {"type":"tool_use","id":"t3","name":"get_calendar_events","input":{}}]}"""),
                ok("""{"stop_reason":"end_turn","content":[]}"""),
            )
        )
        val tools = FakeTools()
        val cb = Cb()
        val r = client(t).ask(AppSettings(), "k", emptyList(), "q", tools, cb)
        // no text in the final reply -> the last tool note is the answer
        assertEquals(AskResult.Answer("Draft ready on your phone."), r)
        assertEquals(listOf(now.toEpochSecond() + 1200 to "Check the oven - now"), cb.reminders)
        assertEquals(listOf("create_draft"), tools.calls.map { it.first })
        assertEquals(listOf("Thinking...", "Still working..."), cb.statuses)
        val results = t.bodies[1]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Reminder set for 12:20", results[0]["content"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), results[2]["is_error"]) // calendar not offered -> Unknown tool
    }

    @Test
    fun pauseTurnContinuesAndStopsAtMaxRounds() = runTest {
        val t = FakeTransport(listOf(ok("""{"stop_reason":"pause_turn","content":[{"type":"server_tool_use","id":"s","name":"web_search","input":{}},{"type":"text","text":"partial"}]}""")))
        val cb = Cb()
        val r = client(t).ask(AppSettings(), "k", emptyList(), "q", FakeTools(), cb)
        assertEquals(ClaudeClient.MAX_ROUNDS, t.bodies.size)
        assertEquals(AskResult.Answer("partial", tainted = true), r) // web search taints
        assertEquals(ClaudeClient.MAX_ROUNDS - 1, cb.statuses.count { it == "Still working..." })
        assertTrue(cb.statuses.contains("Searching the web..."))
        // assistant content appended for continuation
        assertEquals("assistant", t.bodies[1]["messages"]!!.jsonArray.last().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsMapToWatchMessages() = runTest {
        suspend fun err(res: HttpResult) = client(FakeTransport(listOf(res))).ask(AppSettings(), "k", emptyList(), "q", FakeTools(), Cb())
        assertEquals(AskResult.Error(Msg.UNAUTHORIZED), err(HttpResult(401, null, false)))
        assertEquals(AskResult.Error(Msg.BUSY), err(HttpResult(529, null, false)))
        assertEquals(AskResult.Error(Msg.NETWORK), err(HttpResult(0, null, true)))
        val e = err(HttpResult(400, Json.parseToJsonElement("""{"error":{"message":"bad “thing”"}}""").jsonObject, false))
        assertEquals(AskResult.Error("Error 400: bad \"thing\""), e)
        assertEquals(AskResult.Error(Msg.EMPTY), err(ok("""{"stop_reason":"end_turn","content":[]}""")))
        assertEquals(AskResult.Error(Msg.NO_KEY), client(FakeTransport(emptyList())).ask(AppSettings(), "", emptyList(), "q", FakeTools(), Cb()))
    }

    @Test
    fun overallBudgetStopsTheLoop() = runTest {
        var clock = 0L
        val t = object : ClaudeTransport {
            override suspend fun post(apiKey: String, body: JsonObject, timeoutMs: Long): HttpResult {
                clock += 25_000
                return ok("""{"stop_reason":"pause_turn","content":[]}""")
            }
        }
        val r = ClaudeClient(t, clock = { clock }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "q", FakeTools(), Cb())
        assertEquals(AskResult.Error(Msg.TOO_SLOW), r)
        assertTrue(clock <= 75_000)
    }

    @Suppress("unused")
    private fun JsonArray.names() = map { it.jsonObject["name"]!!.jsonPrimitive.content }
}
