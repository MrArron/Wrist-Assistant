package com.mrarron.wristclaude.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SafetyTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---- item 1: approved text == sent text ----

    private suspend fun reply(text: String, title: String = "Mom", outcome: ConfirmOutcome = ConfirmOutcome.APPROVED): Triple<ToolOutcome, String?, String?> {
        var shown: String? = null
        var sent: String? = null
        val r = ReplyFlow.run(title, "Messages", "com.google.android.apps.messaging", text, confirm = { shown = it; outcome }) { sent = it; true }
        return Triple(r, shown, sent)
    }

    @Test
    fun approvedTextIsExactlySentText() = runTest {
        val (r, shown, sent) = reply("  I'll be there at 6, see you soon!  ")
        assertEquals("Sent.", r.content)
        assertEquals("I'll be there at 6, see you soon!", sent)
        assertTrue(shown!!.endsWith("\n" + sent))
        assertTrue(shown!!.startsWith("Reply to Mom in Messages (com.google.android.apps.messaging):"))
        assertTrue(Recipients.isDisplayable(shown!!))
    }

    @Test
    fun overLongReplyRejectedBeforeConfirm() = runTest {
        val (r, shown, sent) = reply("a".repeat(Recipients.MAX_REPLY_CHARS + 1))
        assertTrue(r.isError); assertNull(shown); assertNull(sent)
        // fits in chars but the header pushes it over 300 bytes
        val (r2, shown2, _) = reply("é".repeat(Recipients.MAX_REPLY_CHARS), title = "x".repeat(60))
        assertTrue(r2.isError); assertNull(shown2)
    }

    @Test
    fun markdownEmojiAndSmartQuotesRejected() = runTest {
        for (t in listOf("**on my way**", "on my way 😀", "“ok”", "see `this`", "[x](http://evil)", "你好")) {
            val (r, shown, sent) = reply(t)
            assertTrue(t, r.isError); assertNull(shown); assertNull(sent)
        }
    }

    @Test
    fun declinedIsNotSent() = runTest {
        val (r, _, sent) = reply("ok", outcome = ConfirmOutcome.DECLINED)
        assertEquals("The user declined.", r.content); assertNull(sent)
    }

    @Test
    fun displayableRule() {
        assertTrue(Recipients.isDisplayable("Add event: Dentist\nThu Oct 15 15:00-16:00"))
        assertFalse(Recipients.isDisplayable("x".repeat(301)))
        assertFalse(Recipients.isDisplayable("smart ’ quote"))
    }

    // ---- item 2: taint gate ----

    @Test
    fun taintGateRules() {
        val ev = obj("""{"title":"Meet","start":"2026-10-15T15:00"}""")
        assertFalse(TaintGate.needsConfirm("add_calendar_event", ev, tainted = false))
        assertTrue(TaintGate.needsConfirm("add_calendar_event", ev, tainted = true))
        assertTrue(TaintGate.needsConfirm("create_draft", obj("""{"kind":"text","to":"+13215550100","body":"hi"}"""), true))
        assertFalse(TaintGate.needsConfirm("create_draft", obj("""{"kind":"text","to":"Mom","body":"hi"}"""), true))
        assertFalse(TaintGate.needsConfirm("create_draft", obj("""{"kind":"text","body":"hi"}"""), true))
        for (t in listOf("set_alarm", "set_timer", "set_reminder")) assertFalse(TaintGate.needsConfirm(t, JsonObject(emptyMap()), true))
    }

    private val now = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("America/New_York"))
    private fun ok(json: String) = HttpResult(200, Json.parseToJsonElement(json).jsonObject, false)

    private class Recorder : ToolExecutor {
        val taintSeen = ArrayList<Pair<String, Boolean>>()
        override fun availability() = ToolAvailability(calendarWrite = true, notifications = true)
        override suspend fun execute(name: String, input: JsonObject, ctx: ToolContext): ToolOutcome {
            taintSeen += name to ctx.tainted
            return ToolOutcome("done", note = "Did $name.")
        }
    }

    private val noCb = object : ClaudeClient.Callbacks {
        override suspend fun onStatus(text: String) {}
        override suspend fun onReminder(unix: Long, text: String) {}
    }

    private fun seq(vararg replies: HttpResult): ClaudeTransport {
        var i = 0
        return ClaudeTransport { _, _, _ -> replies[minOf(i++, replies.size - 1)] }
    }

    @Test
    fun notificationsAndWebSearchTaintLaterTools() = runTest {
        val tools = Recorder()
        val t = seq(
            ok("""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"add_calendar_event","input":{}}]}"""),
            ok("""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"b","name":"get_recent_notifications","input":{}}]}"""),
            ok("""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"c","name":"add_calendar_event","input":{}}]}"""),
            ok("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""),
        )
        val r = ClaudeClient(t, clock = { 0L }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "q", tools, noCb)
        assertEquals(listOf("add_calendar_event" to false, "get_recent_notifications" to false, "add_calendar_event" to true), tools.taintSeen)
        assertEquals(AskResult.Answer("ok", tainted = true), r)

        val tools2 = Recorder()
        val t2 = seq(
            ok("""{"stop_reason":"tool_use","content":[{"type":"server_tool_use","id":"s","name":"web_search","input":{}},
                {"type":"web_search_tool_result","tool_use_id":"s","content":[]},
                {"type":"tool_use","id":"d","name":"add_calendar_event","input":{}}]}"""),
            ok("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""),
        )
        ClaudeClient(t2, clock = { 0L }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "q", tools2, noCb)
        assertEquals(listOf("add_calendar_event" to true), tools2.taintSeen)

        // a tainted follow-up starts tainted
        val tools3 = Recorder()
        ClaudeClient(seq(
            ok("""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"e","name":"add_calendar_event","input":{}}]}"""),
            ok("""{"stop_reason":"end_turn","content":[]}"""),
        ), clock = { 0L }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "q", tools3, noCb, initiallyTainted = true)
        assertEquals(listOf("add_calendar_event" to true), tools3.taintSeen)
    }

    // ---- item 3: no error after a side effect ----

    @Test
    fun errorAfterSideEffectReturnsNote() = runTest {
        val t = seq(
            ok("""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"add_calendar_event","input":{}}]}"""),
            HttpResult(0, null, true),
        )
        val r = ClaudeClient(t, clock = { 0L }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "q", Recorder(), noCb)
        assertEquals(AskResult.Answer("Did add_calendar_event."), r)
        val t2 = seq(
            ok("""{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"a","name":"set_reminder","input":{"minutes_from_now":5,"text":"x"}}]}"""),
            HttpResult(529, null, false),
        )
        val r2 = ClaudeClient(t2, clock = { 0L }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "q", Recorder(), noCb)
        assertEquals(AskResult.Answer("Reminder set for 12:05."), r2)
    }
}
