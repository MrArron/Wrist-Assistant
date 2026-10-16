package com.mrarron.wristclaude.core

import kotlinx.serialization.json.JsonObject

/** Result of asking the user to approve something that goes to another person. */
enum class ConfirmOutcome(val toolText: String) {
    APPROVED("Sent."),
    DECLINED("The user declined."),
    NO_ANSWER("No answer from the watch."),
}

/**
 * Shows [text] to the user (on the watch, or in the Test screen) and waits for approve/decline.
 * [text] must already pass [Recipients.isDisplayable]: implementations show it unchanged or not at all.
 */
fun interface Confirmer {
    suspend fun confirm(text: String): ConfirmOutcome
}

/** Pure helpers for tool inputs (unit-tested). */
object Recipients {
    const val MAX_CONFIRM_BYTES = 300
    const val MAX_REPLY_CHARS = 150

    private val EMAIL = Regex("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$")
    private val PHONE = Regex("^\\+?[0-9 ().\\-]{3,24}$")

    fun isEmail(s: String?) = s != null && EMAIL.matches(s.trim())

    fun isPhone(s: String?): Boolean {
        if (s == null) return false
        val t = s.trim()
        return PHONE.matches(t) && t.count { it.isDigit() } >= 3
    }

    /** Returns [to] only if it's usable for this kind of draft (phone for text, email for email). */
    fun cleanTo(kind: String, to: String?): String? {
        val t = to?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (kind) {
            "email" -> t.takeIf { isEmail(it) }
            else -> t.takeIf { isPhone(it) }
        }
    }

    /** True if the watch would show [text] exactly as given. */
    fun isDisplayable(text: String) =
        TextUtil.utf8Bytes(text) <= MAX_CONFIRM_BYTES && TextUtil.plainLatin1(text) == text

    /**
     * A display-only summary (e.g. "Add event: ...") for the watch, sanitized to Latin-1 and capped at 300 bytes.
     * Not for text that will be sent to someone: use [checkReply] for that.
     */
    fun confirmText(header: String, body: String): String =
        TextUtil.truncateBytes(TextUtil.sanitize(header.trim() + "\n" + body.trim()), MAX_CONFIRM_BYTES)

    sealed class ReplyCheck {
        /** [confirmText] = header + "\n" + [text]; [text] is exactly what gets sent. */
        data class Ok(val confirmText: String, val text: String) : ReplyCheck()
        data class Bad(val error: String) : ReplyCheck()
    }

    /** Short, visible form of the posting app's package (spoofed app labels show up here). */
    fun shortPackage(pkg: String) = TextUtil.truncateBytes(pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }, 40)

    /**
     * Builds the watch confirmation for a reply. The reply is accepted only if the watch can show it
     * unchanged (plain Latin-1, no markdown, fits in 300 bytes with the header), so approved text == sent text.
     */
    fun checkReply(title: String, app: String, pkg: String, text: String): ReplyCheck {
        val t = text.trim()
        val err = ReplyCheck.Bad(
            "Not sent. Resend the reply as plain text (letters, digits and basic punctuation; no markdown, emoji, " +
                "smart quotes or line breaks beyond simple ones), at most $MAX_REPLY_CHARS characters."
        )
        if (t.isEmpty() || t.length > MAX_REPLY_CHARS) return err
        if (TextUtil.plainLatin1(t) != t || TextUtil.stripMarkdown(t) != t) return err
        val header = "Reply to " + TextUtil.oneLineCapped(title.ifBlank { "notification" }, 40) +
            " in " + TextUtil.oneLineCapped(app.ifBlank { "app" }, 30) + " (" + shortPackage(pkg) + "):"
        val confirm = header + "\n" + t
        if (!isDisplayable(confirm)) return err
        return ReplyCheck.Ok(confirm, t)
    }
}

/** Reply flow: check, confirm on the watch, then send exactly the approved text. */
object ReplyFlow {
    suspend fun run(
        title: String,
        app: String,
        pkg: String,
        text: String,
        confirm: suspend (String) -> ConfirmOutcome,
        send: (String) -> Boolean,
    ): ToolOutcome {
        val check = Recipients.checkReply(title, app, pkg, text)
        if (check is Recipients.ReplyCheck.Bad) return ToolOutcome.error(check.error)
        check as Recipients.ReplyCheck.Ok
        val outcome = confirm(check.confirmText)
        if (outcome != ConfirmOutcome.APPROVED) {
            return ToolOutcome(outcome.toolText, note = if (outcome == ConfirmOutcome.DECLINED) "Not sent." else "Not sent: no answer from the watch.")
        }
        return if (send(check.text)) ToolOutcome("Sent.", note = "Reply sent.")
        else ToolOutcome.error("Could not send the reply: the notification's reply action no longer works.")
    }
}

/** Which actions need watch approval once untrusted content (web results, notifications) is in the conversation. */
object TaintGate {
    fun needsConfirm(name: String, input: JsonObject, tainted: Boolean): Boolean {
        if (!tainted) return false
        return when (name) {
            "add_calendar_event" -> true
            "create_draft" -> Recipients.cleanTo(if (input.str("kind") == "email") "email" else "text", input.str("to")) != null
            else -> false
        }
    }
}
