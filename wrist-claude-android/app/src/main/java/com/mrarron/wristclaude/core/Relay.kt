package com.mrarron.wristclaude.core

/**
 * "Relay to watch": the companion re-posts notifications from a few apps the user picked (for example an
 * assistant app such as Claude Duet) as its own notification, with Reply and Save to notes actions the
 * Pebble app shows on the watch. Off by default. Pure Kotlin so the rules can be unit-tested.
 */
object Relay {
    const val MAX_APPS = 8
    const val MAX_TITLE = 80
    const val MAX_TEXT = 400
    /** Remembered fingerprints, so an app re-posting the same text (an update to a notification) buzzes once. */
    const val SEEN_MAX = 40

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** Clean, distinct, capped package list; anything that isn't a package name is dropped. */
    fun cleanApps(apps: List<String>): List<String> =
        apps.map { it.trim() }.filter { it.length <= 120 && PACKAGE.matches(it) }.distinct().take(MAX_APPS)

    /** Whether a notification from [pkg] should be relayed. Our own package is never relayed (no loops). */
    fun shouldRelay(enabled: Boolean, apps: List<String>, pkg: String, ownPackage: String): Boolean =
        enabled && pkg != ownPackage && pkg in apps

    /** Watch-friendly title: "App: title" on one line, or just the app name. */
    fun title(app: String, title: String): String {
        val a = oneLine(app).ifBlank { "Notification" }
        val t = oneLine(title)
        val s = if (t.isBlank() || t.equals(a, ignoreCase = true)) a else "$a: $t"
        return s.take(MAX_TITLE)
    }

    /** Body text, whitespace tidied and capped (the watch shows a few lines; the phone keeps the full original). */
    fun text(text: String): String {
        val s = text.replace("\r\n", "\n").replace('\r', '\n').lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT - 1).trimEnd() + "…"
    }

    /** Same key and same text = the same message. */
    fun fingerprint(key: String, title: String, text: String): String = key + "\u0000" + (title + "\u0000" + text).hashCode()

    /** A stable notification id per source notification key, outside the ranges other notifications use. */
    fun notificationId(key: String): Int = 6_000_000 + (key.hashCode() and 0x7fffffff) % 1_000_000

    /** The note saved by the "Save to notes" action. */
    fun noteText(app: String, title: String, text: String): String =
        "From " + oneLine(app).ifBlank { "a phone notification" } + ": " +
            listOf(oneLine(title), text.trim()).filter { it.isNotBlank() }.joinToString("\n")

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Remembers recent fingerprints; [seenBefore] returns true for a repeat. */
    class Seen(private val max: Int = SEEN_MAX) {
        private val items = ArrayDeque<String>()

        @Synchronized
        fun seenBefore(fp: String): Boolean {
            if (fp in items) return true
            items.addLast(fp)
            while (items.size > max) items.removeFirst()
            return false
        }

        @Synchronized
        fun clear() = items.clear()
    }
}
