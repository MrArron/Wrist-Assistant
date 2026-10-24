package com.mrarron.wristclaude.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2.2.0: send to phone, phone location, usage meter. */
class FeaturesTest {
    private val now = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("America/New_York"))

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    private class Tools(private val place: Place? = null) : ToolExecutor {
        val calls = ArrayList<String>()
        override fun availability() = ToolAvailability()
        override suspend fun location(): Place? = place
        override suspend fun execute(name: String, input: JsonObject, ctx: ToolContext): ToolOutcome {
            calls += name
            return ToolOutcome("Shown on the phone.", note = "Sent to your phone.")
        }
    }

    private object NoCb : ClaudeClient.Callbacks {
        override suspend fun onStatus(text: String) {}
        override suspend fun onReminder(unix: Long, text: String) {}
    }

    private class Recording(private val limited: Boolean = false) : UsageMeter {
        val records = ArrayList<Pair<String, Boolean>>()
        override fun overLimit() = limited
        override fun record(model: String, usage: JsonObject, newAsk: Boolean) { records += model to newAsk }
    }

    private class Seq(private val replies: List<String>) : ClaudeTransport {
        val bodies = ArrayList<JsonObject>()
        override suspend fun post(apiKey: String, body: JsonObject, timeoutMs: Long): HttpResult {
            bodies += body
            val r = replies[minOf(bodies.size - 1, replies.size - 1)]
            return HttpResult(200, Json.parseToJsonElement(r).jsonObject, false)
        }
    }

    // ---- send to phone ----

    @Test
    fun sendToPhoneIsOfferedAndExplained() {
        val names = Prompt.toolNames(Prompt.buildTools(AppSettings(), ToolAvailability()))
        assertTrue("send_to_phone" in names)
        assertTrue(Prompt.buildSystemPrompt(AppSettings(), now, ToolAvailability()).contains("call send_to_phone"))
        assertFalse("send_to_phone" in Prompt.toolNames(Prompt.buildTools(AppSettings(), ToolAvailability(drafts = false))))
    }

    @Test
    fun sendToPhoneRunsAsAPhoneTool() = runTest {
        val t = Seq(listOf(
            """{"stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"send_to_phone","input":{"text":"Recipe..."}}]}""",
            """{"stop_reason":"end_turn","content":[]}""",
        ))
        val tools = Tools()
        val r = ClaudeClient(t, clock = { 0L }, nowProvider = { now }).ask(AppSettings(), "k", emptyList(), "send that to my phone", tools, NoCb)
        assertEquals(listOf("send_to_phone"), tools.calls)
        assertEquals(AskResult.Answer("Sent to your phone."), r)
    }

    // ---- location ----

    @Test
    fun placeLabelAndValidation() {
        assertEquals("Merritt Island, Florida, US", Place.of("Merritt Island", "Florida", "us")!!.label())
        assertEquals("Downtown, Orlando, Florida", Place.of("Orlando", "Florida", "USA", "Downtown")!!.label())
        assertNull(Place.of(" ", null, "US"))
        assertEquals("Florida", Place.of(null, "Florida", null)!!.label())
    }

    @Test
    fun phonePlaceWinsOverCitySetting() = runTest {
        val place = Place("Cocoa Beach", "Florida", "US")
        val t = Seq(listOf("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""))
        ClaudeClient(t, clock = { 0L }, nowProvider = { now }).ask(AppSettings(city = "Springfield"), "k", emptyList(), "q", Tools(place), NoCb)
        val body = t.bodies.single()
        val system = body["system"]!!.jsonPrimitive.content
        assertTrue(system.contains("Cocoa Beach, Florida, US"))
        assertFalse(system.contains("Springfield"))
        val ws = body["tools"]!!.jsonArray.map { it.jsonObject }.single { it["name"]?.jsonPrimitive?.content == "web_search" }
        val loc = ws["user_location"]!!.jsonObject
        assertEquals("Cocoa Beach", loc["city"]!!.jsonPrimitive.content)
        assertEquals("Florida", loc["region"]!!.jsonPrimitive.content)
        assertEquals("US", loc["country"]!!.jsonPrimitive.content)
    }

    @Test
    fun citySettingIsUsedWithoutPhonePlace() {
        val s = AppSettings(city = "Springfield")
        assertTrue(Prompt.buildSystemPrompt(s, now, ToolAvailability(), null, null).contains("User location (city): Springfield."))
    }

    @Test
    fun locationSettingsRoundTrip() {
        val s = AppSettings.fromJson(AppSettings(usePhoneLocation = false, monthlyLimitUsd = 5.5).toJson())
        assertFalse(s.usePhoneLocation)
        assertEquals(5.5, s.monthlyLimitUsd, 1e-9)
        assertTrue(AppSettings.fromJson(obj("{}")).usePhoneLocation)
        assertEquals(0.0, AppSettings.validate(AppSettings(monthlyLimitUsd = -3.0)).monthlyLimitUsd, 1e-9)
        assertEquals(Usage.MAX_LIMIT_USD, AppSettings.validate(AppSettings(monthlyLimitUsd = 1e9)).monthlyLimitUsd, 1e-9)
        assertEquals(0.0, AppSettings.validate(AppSettings(monthlyLimitUsd = Double.NaN)).monthlyLimitUsd, 1e-9)
    }

    // ---- usage ----

    @Test
    fun costFromListPrices() {
        val u = obj("""{"input_tokens":1000000,"output_tokens":1000000,"server_tool_use":{"web_search_requests":2}}""")
        assertEquals(0.10 + 0.50 + 0.02, Usage.cost("claude-haiku-5-5", u), 1e-9)
        assertEquals(2.0 + 10.0 + 0.02, Usage.cost("claude-sonnet-5-5", u), 1e-9)
        assertEquals(4.0 + 20.0 + 0.02, Usage.cost("claude-opus-5-5", u), 1e-9)
        val cached = obj("""{"input_tokens":0,"cache_creation_input_tokens":1000000,"cache_read_input_tokens":1000000,"output_tokens":0}""")
        assertEquals(4.0 * 1.25 + 4.0 * 0.1, Usage.cost("claude-opus-5-5", cached), 1e-9)
        assertEquals(2_000_000L, Usage.inputTokens(cached))
    }

    @Test
    fun monthsAddUpAndRollOver() {
        val u = obj("""{"input_tokens":100,"output_tokens":50}""")
        var m = Usage.add(null, "2026-10", "claude-haiku-5-5", u, newAsk = true)
        m = Usage.add(m, "2026-10", "claude-haiku-5-5", u, newAsk = false)
        assertEquals(MonthUsage("2026-10", asks = 1, inputTokens = 200, outputTokens = 100, costUsd = m.costUsd), m)
        val next = Usage.add(m, "2026-11", "claude-haiku-5-5", u, newAsk = true)
        assertEquals(1L, next.asks)
        assertEquals("2026-11", next.month)
        assertEquals(m, MonthUsage.fromJson(m.toJson()))
    }

    @Test
    fun limitOnlyCountsThisMonth() {
        val m = MonthUsage("2026-10", costUsd = 5.0)
        assertTrue(Usage.overLimit(m, "2026-10", 5.0))
        assertFalse(Usage.overLimit(m, "2026-10", 0.0))
        assertFalse(Usage.overLimit(m, "2026-10", 6.0))
        assertFalse(Usage.overLimit(m, "2026-11", 1.0))
        assertFalse(Usage.overLimit(null, "2026-10", 1.0))
    }

    @Test
    fun clientRecordsEachCallAndCountsOneAsk() = runTest {
        val t = Seq(listOf(
            """{"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5},"content":[{"type":"tool_use","id":"t1","name":"send_to_phone","input":{"text":"x"}}]}""",
            """{"stop_reason":"end_turn","usage":{"input_tokens":20,"output_tokens":5},"content":[{"type":"text","text":"Done."}]}""",
        ))
        val meter = Recording()
        ClaudeClient(t, clock = { 0L }, nowProvider = { now }, usage = meter).ask(AppSettings(), "k", emptyList(), "q", Tools(), NoCb)
        assertEquals(listOf("claude-haiku-5-5" to true, "claude-haiku-5-5" to false), meter.records)
    }

    @Test
    fun overLimitStopsBeforeCallingClaude() = runTest {
        val t = Seq(listOf("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""))
        val r = ClaudeClient(t, clock = { 0L }, nowProvider = { now }, usage = Recording(limited = true))
            .ask(AppSettings(), "k", emptyList(), "q", Tools(), NoCb)
        assertEquals(AskResult.Error(Usage.LIMIT_REACHED), r)
        assertTrue(t.bodies.isEmpty())
    }
}
