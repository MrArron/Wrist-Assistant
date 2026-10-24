package com.mrarron.wristclaude.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class QuickPrompt(val label: String, val prompt: String)

/**
 * v1 settings (same fields, limits and defaults as src/pkjs/settings.js) plus the companion-only
 * fields. The API key is NOT part of this object; it lives encrypted in SecureKeyStore.
 */
data class AppSettings(
    val model: String = DEFAULT_MODEL,
    val length: String = "short",
    val webSearch: Boolean = true,
    val units: String = "imperial",
    val city: String = "",
    val aboutMe: String = "",
    val quickPrompts: List<QuickPrompt> = defaultQuickPrompts(),
    // companion-only
    val defaultCalendarId: Long = -1L,
    val speechRate: Float = 1.0f,
    val autoRead: Boolean = false,
    /** Use the phone's city-level location instead of [city] when location permission is granted. */
    val usePhoneLocation: Boolean = true,
    /** Estimated monthly API spend at which asks stop; 0 = no limit. */
    val monthlyLimitUsd: Double = 0.0,
    // notes, handoffs and context (Google Docs)
    /** Doc ids the app created itself (drive.file); "" until created. Only valid with [docsAppCreated]. */
    val notesDoc: String = "",
    val contextDoc: String = "",
    val docsAppCreated: Boolean = false,
    val useContext: Boolean = true,
    val handoffNotification: Boolean = true,
    val tidyDone: Boolean = true,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        val MODELS = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
        val MODEL_LABELS = mapOf(
            "claude-haiku-5-5" to "Claude Haiku 5.5 - fastest/cheapest",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5",
            "claude-opus-5-5" to "Claude Opus 5.5",
        )
        val LENGTHS = listOf("short", "medium", "long")
        val UNITS = listOf("imperial", "metric")
        const val MAX_PROMPTS = 5
        const val MAX_LABEL = 24
        const val MAX_PROMPT = 200
        const val MAX_ABOUT = 300
        const val MAX_CITY = 60
        /** The 2.1.0 default fun fact prompt; saved copies are upgraded to the {topic} version. */
        private const val OLD_FUN_FACT = "Tell me a surprising fun fact."

        fun defaultQuickPrompts() = listOf(
            QuickPrompt("Fun fact", "Tell me a surprising fun fact about {topic}."),
            QuickPrompt("Motivate me", "Give me a short, genuine bit of motivation for right now."),
            QuickPrompt("Word of the day", "Give me an interesting English word of the day with its meaning and an example."),
        )

        private fun clean(v: String, maxChars: Int) = v.trim().take(maxChars)
        private fun oneLine(v: String, maxChars: Int) = clean(v.replace(Regex("[\r\n\t]+"), " "), maxChars)

        /** Port of settings.js validate(): always returns fully valid settings. */
        fun validate(s: AppSettings, keepEmptyPrompts: Boolean = false): AppSettings {
            val prompts = s.quickPrompts.mapNotNull { q ->
                val label = TextUtil.truncateBytes(TextUtil.sanitize(oneLine(q.label, MAX_LABEL)).replace('\n', ' '), MAX_LABEL)
                val prompt = oneLine(q.prompt, MAX_PROMPT).let { if (it == OLD_FUN_FACT) defaultQuickPrompts()[0].prompt else it }
                if (label.isNotEmpty() && prompt.isNotEmpty()) QuickPrompt(label, prompt)
                else if (keepEmptyPrompts) QuickPrompt(label, prompt) else null
            }.take(MAX_PROMPTS)
            return s.copy(
                model = if (s.model in MODELS) s.model else DEFAULT_MODEL,
                length = if (s.length in LENGTHS) s.length else "short",
                units = if (s.units in UNITS) s.units else "imperial",
                city = oneLine(s.city, MAX_CITY),
                aboutMe = clean(s.aboutMe, MAX_ABOUT),
                quickPrompts = prompts,
                speechRate = if (s.speechRate.isFinite()) s.speechRate.coerceIn(0.5f, 2.0f) else 1.0f,
                monthlyLimitUsd = if (s.monthlyLimitUsd.isFinite()) s.monthlyLimitUsd.coerceIn(0.0, Usage.MAX_LIMIT_USD) else 0.0,
                notesDoc = Notes.usableDocId(s.notesDoc, s.docsAppCreated),
                contextDoc = Notes.usableDocId(s.contextDoc, s.docsAppCreated),
                docsAppCreated = s.docsAppCreated,
            )
        }

        fun fromJson(o: JsonObject?): AppSettings {
            if (o == null) return AppSettings()
            val d = AppSettings()
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val qp = (o["quickPrompts"] as? JsonArray)?.mapNotNull { e ->
                val q = e as? JsonObject ?: return@mapNotNull null
                QuickPrompt(
                    (q["label"] as? JsonPrimitive)?.contentOrNull ?: "",
                    (q["prompt"] as? JsonPrimitive)?.contentOrNull ?: "",
                )
            }
            return validate(
                AppSettings(
                    model = str("model") ?: d.model,
                    length = str("length") ?: d.length,
                    webSearch = (o["webSearch"] as? JsonPrimitive)?.booleanOrNull ?: d.webSearch,
                    units = str("units") ?: d.units,
                    city = str("city") ?: "",
                    aboutMe = str("aboutMe") ?: "",
                    quickPrompts = qp ?: d.quickPrompts,
                    defaultCalendarId = (o["defaultCalendarId"] as? JsonPrimitive)?.longOrNull ?: -1L,
                    speechRate = (o["speechRate"]?.jsonPrimitive?.doubleOrNull ?: 1.0).toFloat(),
                    autoRead = (o["autoRead"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    usePhoneLocation = (o["usePhoneLocation"] as? JsonPrimitive)?.booleanOrNull ?: d.usePhoneLocation,
                    monthlyLimitUsd = (o["monthlyLimitUsd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    notesDoc = str("notesDoc") ?: d.notesDoc,
                    contextDoc = str("contextDoc") ?: d.contextDoc,
                    docsAppCreated = (o["docsAppCreated"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    useContext = (o["useContext"] as? JsonPrimitive)?.booleanOrNull ?: d.useContext,
                    handoffNotification = (o["handoffNotification"] as? JsonPrimitive)?.booleanOrNull ?: d.handoffNotification,
                    tidyDone = (o["tidyDone"] as? JsonPrimitive)?.booleanOrNull ?: d.tidyDone,
                )
            )
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("length", length)
        put("webSearch", webSearch)
        put("units", units)
        put("city", city)
        put("aboutMe", aboutMe)
        put("quickPrompts", buildJsonArray {
            quickPrompts.forEach { q -> add(buildJsonObject { put("label", q.label); put("prompt", q.prompt) }) }
        })
        put("defaultCalendarId", defaultCalendarId)
        put("speechRate", speechRate.toDouble())
        put("autoRead", autoRead)
        put("usePhoneLocation", usePhoneLocation)
        put("monthlyLimitUsd", monthlyLimitUsd)
        put("notesDoc", notesDoc)
        put("contextDoc", contextDoc)
        put("docsAppCreated", docsAppCreated)
        put("useContext", useContext)
        put("handoffNotification", handoffNotification)
        put("tidyDone", tidyDone)
    }

    /** QUICK_NAMES payload: labels joined by \n (<= 5 labels, each <= 24 bytes). */
    fun quickNames(): String = quickPrompts.take(5).joinToString("\n") { TextUtil.oneLineCapped(it.label, MAX_LABEL) }
}
