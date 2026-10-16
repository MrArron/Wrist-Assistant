package com.mrarron.wristclaude.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Finds DONE entries in a notes doc (a documents.get response) so the companion can remove them.
 * Pure Kotlin; never looks at anything but entry header lines, and nothing here logs doc text.
 */
object NotesTidy {
    /** A half-open [start, end) range of Docs API indexes to delete. */
    data class DeleteRange(val start: Int, val end: Int)

    private val DONE_HEADER = Regex("^=== DONE \\| .* ===$")

    private fun parse(docJson: String?): JsonObject? =
        runCatching { Json.parseToJsonElement(docJson.orEmpty()) as? JsonObject }.getOrNull()

    /** The revisionId of a documents.get response, or null. */
    fun revisionId(docJson: String?): String? =
        (parse(docJson)?.get("revisionId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun paragraphText(e: JsonObject): String? {
        val p = e["paragraph"] as? JsonObject ?: return null
        val sb = StringBuilder()
        (p["elements"] as? JsonArray)?.forEach { pe ->
            ((pe as? JsonObject)?.get("textRun") as? JsonObject)?.get("content")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /** Blank = a paragraph whose every element is a text run of whitespace only (images, chips etc. are not blank). */
    private fun isBlankPara(e: JsonObject): Boolean {
        val p = e["paragraph"] as? JsonObject ?: return false
        val els = (p["elements"] as? JsonArray) ?: return true
        return els.all { pe ->
            val run = (pe as? JsonObject)?.get("textRun") as? JsonObject ?: return@all false
            ((run["content"] as? JsonPrimitive)?.contentOrNull ?: "").isBlank()
        }
    }

    private fun int(o: JsonObject, k: String): Int? = (o[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    /**
     * Ranges to delete, highest index first (so they can be applied in order without shifting each other).
     * Each DONE entry runs from its header paragraph to the next entry header, or for the last entry to the
     * end of the body minus the final newline. Entries containing anything but paragraphs are skipped.
     * Malformed input gives an empty list.
     */
    fun doneRanges(docJson: String?): List<DeleteRange> {
        val content = ((parse(docJson)?.get("body") as? JsonObject)?.get("content") as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: return emptyList()
        if (content.isEmpty()) return emptyList()
        val bodyEnd = int(content.last(), "endIndex") ?: return emptyList()

        // Entry starts: element positions of header paragraphs, with their status.
        val starts = ArrayList<Pair<Int, Boolean>>() // (element position, isDone)
        content.forEachIndexed { i, e ->
            val text = paragraphText(e)?.trim() ?: return@forEachIndexed
            if (text.startsWith("===")) starts += i to DONE_HEADER.matches(text)
        }
        val out = ArrayList<DeleteRange>()
        starts.forEachIndexed { n, (pos, done) ->
            if (!done) return@forEachIndexed
            val isLast = n == starts.lastIndex
            val nextPos = starts.getOrNull(n + 1)?.first ?: content.size
            val slice = content.subList(pos, nextPos)
            if (slice.any { it["paragraph"] !is JsonObject }) return@forEachIndexed
            // An empty paragraph followed by more text inside an entry is ambiguous (a multi-paragraph note,
            // or something added by hand): skip the whole entry rather than delete it partially.
            var seenBlank = false
            var ambiguous = false
            for (i in 1 until slice.size) {
                if (isBlankPara(slice[i])) seenBlank = true else if (seenBlank) ambiguous = true
            }
            if (ambiguous) return@forEachIndexed
            var start = int(content[pos], "startIndex") ?: return@forEachIndexed
            val end: Int
            if (!isLast) {
                end = int(content[nextPos], "startIndex") ?: return@forEachIndexed
            } else {
                // Up to the trailing run of empty paragraphs (which stays), or the body end minus the final newline.
                var run = slice.size
                while (run > 1 && isBlankPara(slice[run - 1])) run--
                end = if (run < slice.size) int(slice[run], "startIndex") ?: return@forEachIndexed else bodyEnd - 1
            }
            if (isLast && pos >= 2 && isBlankPara(content[pos - 1])) {
                // Entries are written with a leading blank line; take it so no double blank is left behind.
                val blankStart = int(content[pos - 1], "startIndex")
                if (blankStart != null && blankStart >= 1 && (out.lastOrNull()?.end ?: 0) <= blankStart) start = blankStart
            }
            if (start < 1 || end <= start) return@forEachIndexed
            out += DeleteRange(start, end)
        }
        return out.sortedByDescending { it.start }
    }

    /** documents.batchUpdate body: delete [ranges] (already highest first), guarded by [revisionId]. */
    fun deleteRequest(ranges: List<DeleteRange>, revisionId: String): JsonObject = buildJsonObject {
        put("requests", buildJsonArray {
            ranges.sortedByDescending { it.start }.forEach { r ->
                add(buildJsonObject {
                    putJsonObject("deleteContentRange") {
                        putJsonObject("range") {
                            put("startIndex", r.start)
                            put("endIndex", r.end)
                        }
                    }
                })
            }
        })
        putJsonObject("writeControl") { put("requiredRevisionId", revisionId) }
    }
}
