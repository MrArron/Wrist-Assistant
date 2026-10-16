package com.mrarron.wristclaude.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.time.ZonedDateTime

/**
 * The notes / handoff entry format and the Google Docs request/response shapes
 * (see docs/companion.md, "Notes and handoffs" and "Context from the main Claude").
 * Pure Kotlin so it can be unit-tested.
 */
object Notes {
    const val NOTES_TITLE = "Wrist Assistant Notes"
    const val CONTEXT_TITLE = "Wrist Assistant Context"

    const val MSG_SERVICE_DISABLED = "The Google Docs API or Google Drive API is off in your Google Cloud project. Enable both, wait a minute, then Retry."
    const val MSG_DRIVE_DISABLED = "The Google Drive API is off in your Google Cloud project. Enable the Google Drive API, wait a minute, then Retry."
    const val MSG_SCOPE_MISSING = "Google access wasn't granted. Remove Wrist Assistant in your Google Account (Security > Third-party apps & services), then tap Connect and tick the box to let Wrist Assistant use the files it creates. Your old docs stay in Google Drive."
    const val MSG_PERMISSION_DENIED = "Google won't let Wrist Assistant open its doc (a different Google account, or access was removed). Tap Make new docs, or Disconnect and Connect again. Your old docs stay in Google Drive."
    const val MSG_FORBIDDEN_GENERIC = "Google refused access to the doc. Disconnect and Connect again (your old docs stay in Google Drive). If it keeps happening, check that the Docs and Drive APIs are enabled."

    /**
     * Turns a Google 403 JSON error body into a short user-facing message. Only the error status, message
     * and detail reasons are inspected; nothing from the body is echoed back. Unknown bodies get the generic text.
     */
    fun forbiddenMessage(body: String?): String {
        val err = runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonObject }.getOrNull()
            ?: return MSG_FORBIDDEN_GENERIC
        val status = (err["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val message = (err["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reasons = runCatching {
            err["details"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("reason") as? JsonPrimitive }
                .mapNotNull { it.contentOrNull }
        }.getOrDefault(emptyList())
        val errors = runCatching {
            err["errors"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("reason") as? JsonPrimitive }
                .mapNotNull { it.contentOrNull }
        }.getOrDefault(emptyList())
        val all = reasons + errors
        return when {
            "SERVICE_DISABLED" in all || "accessNotConfigured" in all || message.contains("has not been used in project") ||
                message.contains("API has not been enabled") ->
                if (body.orEmpty().contains("drive.googleapis.com")) MSG_DRIVE_DISABLED else MSG_SERVICE_DISABLED
            "ACCESS_TOKEN_SCOPE_INSUFFICIENT" in all || "insufficientPermissions" in all ||
                message.contains("insufficient authentication scopes", ignoreCase = true) ||
                message.contains("insufficient scope", ignoreCase = true) -> MSG_SCOPE_MISSING
            status == "PERMISSION_DENIED" || "PERMISSION_DENIED" in all || "forbidden" in all -> MSG_PERMISSION_DENIED
            else -> MSG_FORBIDDEN_GENERIC
        }
    }

    const val MAX_NOTE_CHARS = 2000
    const val MAX_FIELD_CHARS = 400
    const val HANDOFF_MESSAGES = 6
    const val HANDOFF_ANSWER_CHARS = 600
    const val MAX_CONTEXT_CHARS = 1500
    const val CONTEXT_START = "=== WRIST CONTEXT"
    const val CONTEXT_END = "=== END ==="

    private val DOC_ID = Regex("^[A-Za-z0-9_-]{20,}$")
    private val DOC_LINK = Regex("/document/d/([A-Za-z0-9_-]{20,})")

    /** Accepts a docs.google.com link or a bare document id; null if neither. */
    fun docIdFrom(input: String?): String? {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return null
        DOC_LINK.find(s)?.let { return it.groupValues[1] }
        return s.takeIf { DOC_ID.matches(it) }
    }

    /**
     * A stored doc id is usable only if the app created it (drive.file can't open any other file) and it
     * parses as an id. Anything else, including ids from before the app made its own docs, is "no doc yet" ("").
     */
    fun usableDocId(id: String?, appCreated: Boolean): String = if (appCreated) docIdFrom(id).orEmpty() else ""

    /** documents.create body. */
    fun createRequest(title: String): JsonObject = buildJsonObject { put("title", title) }

    const val NOTES_SEED = NOTES_TITLE + "\n" +
        "Notes and handoffs from the Pebble watch. New entries are added at the bottom. " +
        "Say \"catch up on my notes\" in Claude to work through the OPEN ones.\n"
    const val CONTEXT_PLACEHOLDER = "(not filled yet)"
    const val CONTEXT_SEED = "=== WRIST CONTEXT | " + CONTEXT_PLACEHOLDER + " ===\nNo context yet. Your main Claude fills this in.\n=== END ===\n"

    /**
     * batchUpdate body that writes [text] at the start of a new, empty doc (index 1). The empty doc's own final
     * newline stays last, so the notes seed ends as title, intro, empty line.
     */
    fun seedRequest(text: String): JsonObject = buildJsonObject {
        putJsonArray("requests") {
            addJsonObject {
                putJsonObject("insertText") {
                    put("text", text)
                    putJsonObject("location") { put("index", 1) }
                }
            }
        }
    }

    /** A freshly created doc holds only its final newline. */
    fun isBlankDoc(doc: JsonObject?): Boolean = plainText(doc).isBlank()

    /** Drive files.list query for the app's own doc named [title] (drive.file only lists app-created files). */
    fun driveListQuery(title: String): String =
        "name='${title.replace("\\", "\\\\").replace("'", "\\'")}' and mimeType='application/vnd.google-apps.document' and trashed=false"

    /** Ids from a files.list response, in the order Google returned them (newest first with orderBy). */
    fun fileIds(json: JsonObject?): List<String> =
        (json?.get("files") as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }

    fun docUrl(id: String) = "https://docs.google.com/document/d/$id/edit"

    /** One header field: single line, no field separators or entry markers. */
    fun field(v: String?, maxChars: Int = 60): String =
        (v ?: "").replace(Regex("[\r\n\t]+"), " ").replace("|", "/").replace("===", "").replace(Regex(" {2,}"), " ").trim().take(maxChars)

    /** Body text: keeps line breaks but no line may start an entry header. */
    fun body(v: String?, maxChars: Int = MAX_NOTE_CHARS): String =
        (v ?: "").replace("\r\n", "\n").replace('\r', '\n').trim().take(maxChars)
            .lines().joinToString("\n") { if (it.trimStart().startsWith("===")) "- " + it.trimStart().trimStart('=').trim() else it.trimEnd() }

    fun stamp(now: ZonedDateTime): String =
        "%04d-%02d-%02d %02d:%02d".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)

    fun header(kind: String, project: String?, now: ZonedDateTime) =
        "=== OPEN | ${stamp(now)} | $kind | ${field(project)} ==="

    /** A NOTE or FOLLOWUP entry, ending with a blank line so the next entry starts cleanly. */
    fun noteEntry(text: String, followup: Boolean, project: String?, now: ZonedDateTime): String =
        "\n" + header(if (followup) "FOLLOWUP" else "NOTE", project, now) + "\n" + body(text) + "\n"

    /** A HANDOFF entry; [conversation] is the exact recent exchange (the companion adds it, not Claude). */
    fun handoffEntry(
        title: String,
        project: String?,
        goal: String?,
        status: String?,
        nextStep: String?,
        conversation: List<ChatMessage>,
        now: ZonedDateTime,
    ): String {
        val sb = StringBuilder("\n")
        sb.append(header("HANDOFF", project?.takeIf { it.isNotBlank() } ?: title, now)).append('\n')
        sb.append("Title: ").append(field(title, 120)).append('\n')
        sb.append("Goal: ").append(field(goal, MAX_FIELD_CHARS)).append('\n')
        sb.append("Where it stands: ").append(field(status, MAX_FIELD_CHARS)).append('\n')
        sb.append("Next step: ").append(field(nextStep, MAX_FIELD_CHARS)).append('\n')
        val convo = conversation.filter { it.role == "user" || it.role == "assistant" }.takeLast(HANDOFF_MESSAGES)
        if (convo.isNotEmpty()) {
            sb.append("Conversation:\n")
            convo.forEach { m ->
                val text = if (m.role == "assistant") m.content.take(HANDOFF_ANSWER_CHARS) else m.content.take(MAX_FIELD_CHARS)
                sb.append(if (m.role == "user") "Q: " else "A: ").append(field(text, HANDOFF_ANSWER_CHARS)).append('\n')
            }
        }
        return sb.toString()
    }

    /** documents.batchUpdate body: append [text] at the end of the body. */
    fun appendRequest(text: String): JsonObject = buildJsonObject {
        putJsonArray("requests") {
            addJsonObject {
                putJsonObject("insertText") {
                    put("text", text)
                    putJsonObject("endOfSegmentLocation") {}
                }
            }
        }
    }

    /** Plain text of a documents.get response (body paragraphs only, tables flattened). */
    fun plainText(doc: JsonObject?): String {
        val content = ((doc?.get("body") as? JsonObject)?.get("content") as? JsonArray) ?: return ""
        val sb = StringBuilder()
        fun walk(elements: JsonArray) {
            for (e in elements) {
                val o = e as? JsonObject ?: continue
                (o["paragraph"] as? JsonObject)?.let { p ->
                    (p["elements"] as? JsonArray)?.forEach { pe ->
                        val run = (pe as? JsonObject)?.get("textRun") as? JsonObject
                        (run?.get("content") as? JsonPrimitive)?.contentOrNull?.let { sb.append(it) }
                    }
                }
                (o["table"] as? JsonObject)?.let { t ->
                    (t["tableRows"] as? JsonArray)?.forEach { r ->
                        ((r as? JsonObject)?.get("tableCells") as? JsonArray)?.forEach { c ->
                            ((c as? JsonObject)?.get("content") as? JsonArray)?.let { walk(it) }
                        }
                    }
                }
            }
        }
        walk(content)
        return sb.toString()
    }

    /**
     * The context block from the context doc: the text from the start marker to the end marker
     * (inclusive of the header line, which carries the refresh time), sanitized and capped.
     * Returns null when the doc has no context block.
     */
    fun extractContext(docText: String): String? {
        val start = docText.indexOf(CONTEXT_START)
        if (start < 0) return null
        val headerEnd = docText.indexOf('\n', start).let { if (it < 0) docText.length else it }
        if (docText.substring(start, headerEnd).contains(CONTEXT_PLACEHOLDER)) return null
        val end = docText.indexOf(CONTEXT_END, start)
        val block = if (end < 0) docText.substring(start) else docText.substring(start, end)
        val clean = TextUtil.sanitize(block).lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n").trim()
        if (clean.isEmpty()) return null
        return clean.take(MAX_CONTEXT_CHARS)
    }

    /** The open/waiting entry count in a notes doc (for the Notes screen). */
    fun openCount(docText: String): Int = Regex("^=== (OPEN|WAITING) \\|", RegexOption.MULTILINE).findAll(docText).count()

    const val HANDOFF_ASK =
        "Hand off this conversation now: call create_handoff with a short title, the goal, where it stands and the next step " +
            "(and the project name if it matches one in the background). Then reply in one short line."
}
