package com.mrarron.wristclaude.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.YearMonth

/** Records what each API call used; blocks new asks once the monthly limit is reached. */
interface UsageMeter {
    fun overLimit(): Boolean = false
    /** [newAsk] is true for the first API call of a question. */
    fun record(model: String, usage: JsonObject, newAsk: Boolean) {}

    companion object {
        val NONE = object : UsageMeter {}
    }
}

/** One month of usage. [costUsd] is an estimate from list prices, not the bill. */
data class MonthUsage(
    val month: String,
    val asks: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val searches: Long = 0,
    val costUsd: Double = 0.0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("month", month); put("asks", asks); put("inputTokens", inputTokens)
        put("outputTokens", outputTokens); put("searches", searches); put("costUsd", costUsd)
    }

    companion object {
        fun fromJson(o: JsonObject?): MonthUsage? {
            val month = (o?.get("month") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            return MonthUsage(month, l("asks"), l("inputTokens"), l("outputTokens"), l("searches"),
                (o["costUsd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
        }
    }
}

object Usage {
    /** USD per million tokens (input, output), Anthropic list prices. */
    private val PRICES = mapOf(
        "claude-haiku-5-5" to (0.10 to 0.50),
        "claude-sonnet-5-5" to (2.00 to 10.00),
        "claude-opus-5-5" to (4.00 to 20.00),
    )
    const val SEARCH_USD = 0.01
    const val MAX_LIMIT_USD = 1000.0

    fun monthKey(m: YearMonth): String = m.toString() // 2026-10

    private fun JsonObject.n(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    /** Input tokens including cache writes and reads. */
    fun inputTokens(usage: JsonObject): Long =
        usage.n("input_tokens") + usage.n("cache_creation_input_tokens") + usage.n("cache_read_input_tokens")

    fun searches(usage: JsonObject): Long = (usage["server_tool_use"] as? JsonObject)?.n("web_search_requests") ?: 0L

    fun cost(model: String, usage: JsonObject): Double {
        val (inP, outP) = PRICES[model] ?: PRICES.getValue(AppSettings.DEFAULT_MODEL)
        val input = usage.n("input_tokens") + usage.n("cache_creation_input_tokens") * 1.25 + usage.n("cache_read_input_tokens") * 0.1
        return (input * inP + usage.n("output_tokens") * outP) / 1_000_000.0 + searches(usage) * SEARCH_USD
    }

    /** Adds one API call to [current], starting a new month when [month] has moved on. */
    fun add(current: MonthUsage?, month: String, model: String, usage: JsonObject, newAsk: Boolean): MonthUsage {
        val base = current?.takeIf { it.month == month } ?: MonthUsage(month)
        return base.copy(
            asks = base.asks + if (newAsk) 1 else 0,
            inputTokens = base.inputTokens + inputTokens(usage),
            outputTokens = base.outputTokens + usage.n("output_tokens"),
            searches = base.searches + searches(usage),
            costUsd = base.costUsd + cost(model, usage),
        )
    }

    /** A limit of 0 means no limit. */
    fun overLimit(current: MonthUsage?, month: String, limitUsd: Double): Boolean =
        limitUsd > 0 && current != null && current.month == month && current.costUsd >= limitUsd

    const val LIMIT_REACHED = "Monthly spending limit reached. Change it in the Wrist Assistant phone app."
}
