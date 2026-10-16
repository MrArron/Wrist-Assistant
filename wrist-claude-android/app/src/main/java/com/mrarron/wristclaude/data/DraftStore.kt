package com.mrarron.wristclaude.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class Draft(
    val id: Long,
    val kind: String, // "text" | "email"
    val to: String?,
    val subject: String?,
    val body: String,
    val createdAt: Long,
)

/** Recent drafts (newest first, max 30), kept on the phone so they can be reopened. */
object DraftStore {
    private const val PREFS = "drafts"
    private const val KEY = "list"
    private const val MAX = 30

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts

    fun init(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return
        _drafts.value = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            Draft(
                id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                kind = s("kind") ?: "text",
                to = s("to"),
                subject = s("subject"),
                body = s("body") ?: "",
                createdAt = (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }

    fun get(id: Long): Draft? = _drafts.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(ctx: Context, kind: String, to: String?, subject: String?, body: String): Draft {
        val now = System.currentTimeMillis()
        val id = maxOf(now, (_drafts.value.maxOfOrNull { it.id } ?: 0L) + 1)
        val d = Draft(id, kind, to, subject, body, now)
        persist(ctx, (listOf(d) + _drafts.value).take(MAX))
        return d
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) = persist(ctx, _drafts.value.filterNot { it.id == id })

    private fun persist(ctx: Context, list: List<Draft>) {
        _drafts.value = list
        val json = buildJsonArray {
            list.forEach { d ->
                add(buildJsonObject {
                    put("id", d.id); put("kind", d.kind); put("to", d.to); put("subject", d.subject)
                    put("body", d.body); put("createdAt", d.createdAt)
                })
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, json.toString()) }
    }
}
