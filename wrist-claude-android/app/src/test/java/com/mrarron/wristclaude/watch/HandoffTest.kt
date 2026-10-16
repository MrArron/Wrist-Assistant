package com.mrarron.wristclaude.watch

import com.mrarron.wristclaude.core.AppSettings
import com.mrarron.wristclaude.core.ChatMessage
import com.mrarron.wristclaude.core.ClaudeClient
import com.mrarron.wristclaude.core.ClaudeTransport
import com.mrarron.wristclaude.core.HttpResult
import com.mrarron.wristclaude.core.Notes
import com.mrarron.wristclaude.core.ToolAvailability
import com.mrarron.wristclaude.core.ToolContext
import com.mrarron.wristclaude.core.ToolExecutor
import com.mrarron.wristclaude.core.ToolOutcome
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandoffTest {
    private class FakeWatch : WatchTransport {
        val sent = ArrayList<PebbleDictionary>()
        override suspend fun send(data: PebbleDictionary): Boolean { sent += data; return true }
    }

    private class Recorder(val notes: Boolean) : ToolExecutor {
        val calls = ArrayList<Pair<String, List<ChatMessage>>>()
        val systems = ArrayList<String>()
        override fun availability() = ToolAvailability(notes = notes)
        override fun background() = "=== WRIST CONTEXT | t ===\n- Garden plan: choosing raised-bed layout"
        override suspend fun execute(name: String, input: JsonObject, ctx: ToolContext): ToolOutcome {
            calls += name to ctx.history
            return ToolOutcome("Handoff saved.", note = "Handoff saved to your notes.")
        }
    }

    private class Env(val tools: Recorder) : AskEngine.Env {
        override fun settings() = AppSettings(webSearch = false)
        override fun apiKey() = "sk-test"
        override fun hasKey() = true
        override fun tools(): ToolExecutor = tools
        override fun speak(on: Boolean) {}
        override fun onAnswer(answer: String) {}
        override suspend fun <T> keepAlive(block: suspend () -> T): T = block()
    }

    /** Tool call for the handoff instruction, then a one-line reply; plain answers otherwise. */
    private fun transport(rec: Recorder) = ClaudeTransport { _, body, _ ->
        rec.systems += (body["system"] as JsonPrimitive).content
        val msgs = body["messages"] as JsonArray
        val last = msgs.last().jsonObject
        val lastContent = last["content"]
        val json = when {
            lastContent is JsonPrimitive && lastContent.content.startsWith(Notes.HANDOFF_ASK) ->
                """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"create_handoff","input":{"title":"Weekend trip","goal":"g","status":"s","next_step":"n"}}]}"""
            lastContent is JsonArray -> """{"stop_reason":"end_turn","content":[{"type":"text","text":"Handed off - say catch up on my notes in Claude."}]}"""
            else -> """{"stop_reason":"end_turn","content":[{"type":"text","text":"Answer to ${(lastContent as JsonPrimitive).content}"}]}"""
        }
        HttpResult(200, Json.parseToJsonElement(json).jsonObject, false)
    }

    private fun TestScope.settle() { advanceTimeBy(5_000); runCurrent() }

    private fun text(d: PebbleDictionary, key: UInt) = d.text(key)

    private fun TestScope.engine(rec: Recorder, watch: FakeWatch): AskEngine {
        val link = WatchLink(watch, backgroundScope, retryDelayMs = 10)
        return AskEngine(this, link, WatchConfirmer(link), ClaudeClient(transport(rec), clock = { 0L }), Env(rec))
    }

    @Test
    fun handoffSendsTheExactConversationAndKeepsItForFollowUps() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val rec = Recorder(notes = true)
        val e = engine(rec, watch)
        e.onMessage(dict(Keys.ASK_KIND to 0, Keys.ASK_TEXT to "which campsites are close", Keys.ROUTE to 1)); settle()
        watch.sent.clear()

        e.onMessage(dict(Keys.ASK_KIND to 3, Keys.ROUTE to 1)); settle()
        val (name, history) = rec.calls.single()
        assertEquals("create_handoff", name)
        assertEquals(listOf(ChatMessage("user", "which campsites are close"), ChatMessage("assistant", "Answer to which campsites are close")), history)
        assertEquals("Handed off - say catch up on my notes in Claude.", watch.sent.mapNotNull { text(it, Keys.CHUNK) }.joinToString(""))
        // background from the context doc reached the system prompt
        assertTrue(rec.systems.last().contains("- Garden plan: choosing raised-bed layout"))

        // A follow-up after the handoff continues the real conversation, without the handoff instruction.
        rec.calls.clear(); watch.sent.clear()
        e.onMessage(dict(Keys.ASK_KIND to 1, Keys.ASK_TEXT to "and prices?", Keys.ROUTE to 1)); settle()
        e.onMessage(dict(Keys.ASK_KIND to 3, Keys.ROUTE to 1)); settle()
        val h2 = rec.calls.single().second
        assertEquals(4, h2.size)
        assertTrue(h2.none { it.content.startsWith(Notes.HANDOFF_ASK) })
    }

    @Test
    fun handoffNeedsAConversationAndGoogle() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val noGoogle = Recorder(notes = false)
        val e = engine(noGoogle, watch)
        e.onMessage(dict(Keys.ASK_KIND to 3, Keys.ROUTE to 1)); settle()
        assertEquals("Nothing to hand off yet. Ask something first.", text(watch.sent.last(), Keys.ERROR))
        e.onMessage(dict(Keys.ASK_KIND to 0, Keys.ASK_TEXT to "hi", Keys.ROUTE to 1)); settle()
        e.onMessage(dict(Keys.ASK_KIND to 3, Keys.ROUTE to 1)); settle()
        assertTrue(text(watch.sent.last(), Keys.ERROR)!!.startsWith("Hand off needs a Google account"))
        assertTrue(noGoogle.calls.isEmpty())
    }
}
