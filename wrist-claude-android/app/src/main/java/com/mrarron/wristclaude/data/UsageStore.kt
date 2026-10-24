package com.mrarron.wristclaude.data

import android.content.Context
import androidx.core.content.edit
import com.mrarron.wristclaude.core.MonthUsage
import com.mrarron.wristclaude.core.Usage
import com.mrarron.wristclaude.core.UsageMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.YearMonth

/** This month's API usage (and last month's, for comparison). Counts only, never questions or answers. */
object UsageStore : UsageMeter {
    private const val PREFS = "usage"
    private const val KEY_CURRENT = "current"
    private const val KEY_PREVIOUS = "previous"

    private lateinit var appCtx: Context
    private val _current = MutableStateFlow<MonthUsage?>(null)
    val current: StateFlow<MonthUsage?> = _current
    private val _previous = MutableStateFlow<MonthUsage?>(null)
    val previous: StateFlow<MonthUsage?> = _previous

    private fun month() = Usage.monthKey(YearMonth.now())

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun read(k: String) = p.getString(k, null)?.let { runCatching { MonthUsage.fromJson(Json.parseToJsonElement(it) as? JsonObject) }.getOrNull() }
        _current.value = read(KEY_CURRENT)
        _previous.value = read(KEY_PREVIOUS)
        rollOver()
    }

    /** Moves a finished month into "previous". */
    @Synchronized
    fun rollOver() {
        val cur = _current.value ?: return
        if (cur.month == month()) return
        _previous.value = cur
        _current.value = null
        persist()
    }

    override fun overLimit(): Boolean {
        rollOver()
        return Usage.overLimit(_current.value, month(), SettingsStore.current().monthlyLimitUsd)
    }

    @Synchronized
    override fun record(model: String, usage: JsonObject, newAsk: Boolean) {
        rollOver()
        _current.value = Usage.add(_current.value, month(), model, usage, newAsk)
        persist()
    }

    @Synchronized
    fun reset() {
        _current.value = null
        _previous.value = null
        persist()
    }

    private fun persist() {
        if (!::appCtx.isInitialized) return
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            _current.value?.let { putString(KEY_CURRENT, it.toJson().toString()) } ?: remove(KEY_CURRENT)
            _previous.value?.let { putString(KEY_PREVIOUS, it.toJson().toString()) } ?: remove(KEY_PREVIOUS)
        }
    }
}
