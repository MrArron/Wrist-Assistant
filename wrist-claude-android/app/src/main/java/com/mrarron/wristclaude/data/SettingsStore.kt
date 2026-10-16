package com.mrarron.wristclaude.data

import android.content.Context
import androidx.core.content.edit
import com.mrarron.wristclaude.core.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** v1 + companion settings, stored as JSON (same field names as wc_settings). No API key here. */
object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY = "wc_settings"

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    fun init(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val obj = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        _settings.value = AppSettings.fromJson(obj)
    }

    fun current(): AppSettings = _settings.value

    /** Atomic read-modify-write: [f] sees the latest stored settings. */
    @Synchronized
    fun update(ctx: Context, f: (AppSettings) -> AppSettings): AppSettings = save(ctx, f(_settings.value))

    @Synchronized
    fun save(ctx: Context, s: AppSettings): AppSettings {
        val v = AppSettings.validate(s)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, v.toJson().toString()) }
        _settings.value = v
        return v
    }
}
