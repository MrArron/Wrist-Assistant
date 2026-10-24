package com.mrarron.wristclaude.watch

import com.mrarron.wristclaude.core.AppSettings
import com.mrarron.wristclaude.core.ClaudeClient
import com.mrarron.wristclaude.core.ClaudeTransport
import com.mrarron.wristclaude.core.ConfirmOutcome
import com.mrarron.wristclaude.core.HttpResult
import com.mrarron.wristclaude.core.ToolAvailability
import com.mrarron.wristclaude.core.ToolContext
import com.mrarron.wristclaude.core.ToolExecutor
import com.mrarron.wristclaude.core.ToolOutcome
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolTest {
    private class FakeWatch(var failFirst: Int = 0) : WatchTransport {
        val sent = ArrayList<PebbleDictionary>()
        var attempts = 0
        override suspend fun send(data: PebbleDictionary): Boolean {
            attempts++
            if (failFirst > 0) {
                failFirst--; return false
            }
            sent += data
            return true
        }
    }

    private class Env(var key: String? = "sk-test") : AskEngine.Env {
        var spoke = ArrayList<Boolean>()
        var answers = ArrayList<String>()
        var settings = AppSettings()
        override fun settings() = settings
        override fun apiKey() = key
        override fun hasKey() = key != null
        override fun tools(): ToolExecutor = object : ToolExecutor {
            override fun availability() = ToolAvailability()
            override suspend fun execute(name: String, input: JsonObject, ctx: ToolContext) = ToolOutcome("ok")
        }
        override fun speak(on: Boolean) { spoke += on }
        override fun onAnswer(answer: String) { answers += answer }
        override suspend fun <T> keepAlive(block: suspend () -> T): T = block()
    }

    private fun answerTransport(text: String) = ClaudeTransport { _, _, _ ->
        HttpResult(200, Json.parseToJsonElement("""{"stop_reason":"end_turn","content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(text))}}]}""").jsonObject, false)
    }

    private fun TestScope.setup(watch: FakeWatch, env: Env, answer: String = "Hello from Claude"): Triple<AskEngine, WatchLink, WatchConfirmer> {
        val link = WatchLink(watch, backgroundScope, retryDelayMs = 10)
        val confirmer = WatchConfirmer(link)
        val engine = AskEngine(this, link, confirmer, ClaudeClient(answerTransport(answer), clock = { 0L }), env)
        return Triple(engine, link, confirmer)
    }

    /** The link pump lives in backgroundScope, which advanceUntilIdle() doesn't wait for. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    private fun PebbleDictionary.ints() = filterValues { it is PebbleDictionaryItem.Int32 }.mapValues { (it.value as PebbleDictionaryItem.Int32).value }

    @Test
    fun helloHasCompanionReadyAndQuickNamesAsInt32() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val (engine) = setup(watch, Env())
        engine.sendHello()
        settle()
        val d = watch.sent.single()
        assertEquals(mapOf(Keys.COMPANION to 1, Keys.READY to 1), d.ints())
        assertEquals(PebbleDictionaryItem.Text("Fun fact\nMotivate me\nWord of the day"), d[Keys.QUICK_NAMES])
    }

    @Test
    fun readyFromWatchTriggersHelloWithKeyMissing() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val (engine) = setup(watch, Env(key = null))
        assertTrue(engine.onMessage(dict(Keys.READY to 1)))
        settle()
        assertEquals(0, watch.sent.single().ints()[Keys.READY])
    }

    @Test
    fun asksWithoutRouteAreIgnored() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val env = Env()
        val (engine) = setup(watch, env)
        assertFalse(engine.onMessage(dict(Keys.ASK_KIND to 0, Keys.ASK_TEXT to "hi")))
        assertFalse(engine.onMessage(dict(Keys.ASK_KIND to 0, Keys.ASK_TEXT to "hi", Keys.ROUTE to 0)))
        assertFalse(engine.onMessage(dict(Keys.SPEAK to 1)))
        assertFalse(engine.onMessage(dict(Keys.CONFIRM_ID to 5, Keys.CONFIRM_RESULT to 1)))
        settle()
        assertTrue(watch.sent.isEmpty())
        assertTrue(env.spoke.isEmpty())
    }

    @Test
    fun routedAskProducesStatusThenChunksWithFinal() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val env = Env()
        val long = (1..120).joinToString(" ") { "word$it" }
        val (engine) = setup(watch, env, answer = long)
        // UInt32 ints (as the receiver may deliver them) must be accepted too
        assertTrue(engine.onMessage(mapOf(
            Keys.ASK_KIND to PebbleDictionaryItem.UInt32(0u),
            Keys.ASK_TEXT to PebbleDictionaryItem.Text("question"),
            Keys.ROUTE to PebbleDictionaryItem.UInt32(1u),
        )))
        settle()
        assertEquals(PebbleDictionaryItem.Text("Thinking..."), watch.sent.first()[Keys.STATUS])
        val chunks = watch.sent.filter { it.containsKey(Keys.CHUNK) }
        assertTrue(chunks.size > 1)
        assertEquals(long, chunks.joinToString("") { (it[Keys.CHUNK] as PebbleDictionaryItem.Text).value })
        assertEquals(1, chunks.last().ints()[Keys.CHUNK_FINAL])
        chunks.dropLast(1).forEach { assertFalse(it.containsKey(Keys.CHUNK_FINAL)) }
        chunks.forEach { assertTrue((it[Keys.CHUNK] as PebbleDictionaryItem.Text).size <= 180) }
        assertEquals(listOf(long), env.answers)
    }

    @Test
    fun quickPromptAndBadIndex() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val (engine) = setup(watch, Env())
        engine.onMessage(dict(Keys.ASK_KIND to 2, Keys.QUICK_INDEX to 9, Keys.ROUTE to 1))
        settle()
        assertEquals(PebbleDictionaryItem.Text("Quick prompt not found. Check settings."), watch.sent.single()[Keys.ERROR])
    }

    @Test
    fun speakRouted() = runTest(StandardTestDispatcher()) {
        val env = Env()
        val (engine) = setup(FakeWatch(), env)
        engine.onMessage(dict(Keys.SPEAK to 1, Keys.ROUTE to 1))
        engine.onMessage(dict(Keys.SPEAK to 0, Keys.ROUTE to 1))
        assertEquals(listOf(true, false), env.spoke)
    }

    @Test
    fun linkRetriesThreeTimesThenDrops() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch(failFirst = 10)
        val link = WatchLink(watch, backgroundScope, retryDelayMs = 10)
        val ok = async { link.sendAndWait(dict(Keys.STATUS to "a")) }
        settle()
        assertFalse(ok.await())
        assertEquals(4, watch.attempts) // 1 try + 3 retries
        watch.failFirst = 2
        val ok2 = async { link.sendAndWait(dict(Keys.STATUS to "b")) }
        settle()
        assertTrue(ok2.await())
    }

    @Test
    fun hungSendTimesOutAndRetries() = runTest(StandardTestDispatcher()) {
        var calls = 0
        val hanging = WatchTransport { calls++; if (calls == 1) kotlinx.coroutines.awaitCancellation() else true }
        val link = WatchLink(hanging, backgroundScope, retryDelayMs = 10, sendTimeoutMs = 10_000)
        val ok = async { link.sendAndWait(dict(Keys.STATUS to "a")) }
        advanceTimeBy(9_000); runCurrent()
        assertFalse(ok.isCompleted)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(ok.await())
        assertEquals(2, calls)
    }

    @Test
    fun undisplayableConfirmIsNeverSent() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val (_, _, confirmer) = setup(watch, Env())
        val r = async { confirmer.confirm("**bold** \u201cquote\u201d") }
        settle()
        assertEquals(ConfirmOutcome.NO_ANSWER, r.await())
        assertTrue(watch.sent.none { it.containsKey(Keys.CONFIRM_TEXT) })
    }

    @Test
    fun confirmMatchesIdAndTimesOut() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val env = Env()
        val (engine, _, confirmer) = setup(watch, env)
        val r = async { confirmer.confirm("Reply to Mom in Messages:\nI'll be there at 6") }
        advanceTimeBy(100)
        val req = watch.sent.first { it.containsKey(Keys.CONFIRM_TEXT) }
        val id = req.ints()[Keys.CONFIRM_ID]!!
        engine.onMessage(dict(Keys.CONFIRM_ID to id + 1, Keys.CONFIRM_RESULT to 1, Keys.ROUTE to 1)) // wrong id: ignored
        advanceTimeBy(100)
        assertFalse(r.isCompleted)
        engine.onMessage(dict(Keys.CONFIRM_ID to id, Keys.CONFIRM_RESULT to 0, Keys.ROUTE to 1))
        settle()
        assertEquals(ConfirmOutcome.DECLINED, r.await())

        val r2 = async { confirmer.confirm("x") }
        advanceTimeBy(59_000)
        assertFalse(r2.isCompleted)
        advanceTimeBy(2_000)
        assertEquals(ConfirmOutcome.NO_ANSWER, r2.await())
    }
    @Test
    fun topicQuickPromptIsRandomizedAndAvoidsLastAnswer() = runTest(StandardTestDispatcher()) {
        val watch = FakeWatch()
        val env = Env()
        val asked = ArrayList<String>()
        val transport = ClaudeTransport { _, body, _ ->
            val last = body["messages"]!!.jsonArray.last().jsonObject["content"]
            asked += (last as? kotlinx.serialization.json.JsonPrimitive)?.content ?: last.toString()
            HttpResult(200, Json.parseToJsonElement("""{"stop_reason":"end_turn","content":[{"type":"text","text":"Fact number ${asked.size}"}]}""").jsonObject, false)
        }
        val link = WatchLink(watch, backgroundScope, retryDelayMs = 10)
        val engine = AskEngine(this, link, WatchConfirmer(link), ClaudeClient(transport, clock = { 0L }), env, kotlin.random.Random(7))
        engine.onMessage(dict(Keys.ASK_KIND to 2, Keys.QUICK_INDEX to 0, Keys.ROUTE to 1))
        settle()
        engine.onMessage(dict(Keys.ASK_KIND to 2, Keys.QUICK_INDEX to 0, Keys.ROUTE to 1))
        settle()
        engine.onMessage(dict(Keys.ASK_KIND to 2, Keys.QUICK_INDEX to 1, Keys.ROUTE to 1))
        settle()
        assertEquals(3, asked.size)
        assertTrue(asked[0], asked[0].startsWith("Tell me a surprising fun fact about ") && !asked[0].contains("{topic}"))
        assertFalse(asked[0].contains("Do not repeat"))
        assertTrue(asked[1], asked[1].contains("Do not repeat any of these: \"Fact number 1\""))
        assertTrue(asked[2], asked[2].contains("Give me a short, genuine bit of motivation for right now."))
        assertFalse(asked[2].contains("Do not repeat"))
    }
}
