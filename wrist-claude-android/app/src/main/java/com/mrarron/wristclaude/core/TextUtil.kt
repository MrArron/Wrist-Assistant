package com.mrarron.wristclaude.core

/**
 * Pure text helpers, ported 1:1 from the watch project's src/pkjs/text.js.
 * Output of [sanitize] contains only Latin-1 characters the watch fonts can draw.
 */
object TextUtil {
    const val CHUNK_BYTES = 180
    const val MAX_ANSWER_BYTES = 1500
    const val MAX_ERROR_BYTES = 180

    private val MAP: Map<Int, String> = buildMap {
        fun put(codes: IntArray, rep: String) = codes.forEach { put(it, rep) }
        put(intArrayOf(0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x02BC), "'")
        put(intArrayOf(0x201C, 0x201D, 0x201E, 0x201F, 0x2033), "\"")
        put(intArrayOf(0x2010, 0x2011, 0x2012, 0x2013, 0x2212), "-")
        put(intArrayOf(0x2014, 0x2015), "-")
        put(intArrayOf(0x2026), "...")
        put(intArrayOf(0x2022, 0x25CF, 0x25AA, 0x2023), "-")
        put(intArrayOf(0x2192), "->")
        put(intArrayOf(0x2190), "<-")
        put(intArrayOf(0x00A0, 0x2002, 0x2003, 0x2009, 0x200A, 0x202F, 0x2007, 0x3000), " ")
        put(intArrayOf(0x00AD, 0x200B, 0x200C, 0x200D, 0xFEFF, 0x2060), "")
    }

    private val MD_RULES: List<Pair<Regex, String>> = listOf(
        Regex("\r\n?") to "\n",
        Regex("```[A-Za-z0-9_+-]*\n?") to "",
        Regex("!\\[([^\\]]*)\\]\\([^)]*\\)") to "$1",
        Regex("\\[([^\\]]+)\\]\\([^)]*\\)") to "$1",
        Regex("(?m)^[ \t]{0,3}#{1,6}[ \t]+") to "",
        Regex("(?m)^[ \t]*([-*_])([ \t]*\\1){2,}[ \t]*$") to "",
        Regex("(?m)^[ \t]*[-*+][ \t]+") to "- ",
        Regex("(?m)^[ \t]*>[ \t]?") to "",
        Regex("(\\*\\*|__)(.+?)\\1") to "$2",
        Regex("\\*([^*\n]+)\\*") to "$1",
        Regex("(^|[^A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])") to "$1$2",
        Regex("`([^`\n]*)`") to "$1",
        Regex("`") to "",
    )

    fun stripMarkdown(input: String): String =
        MD_RULES.fold(input) { s, (re, rep) -> re.replace(s, rep) }

    private val TRAILING_WS_BEFORE_NL = Regex("[ \t]+\n")
    private val MULTI_SPACE = Regex(" {2,}")
    private val MANY_NL = Regex("\n{3,}")
    private val EDGE_WS = Regex("^\\s+|\\s+$")

    fun sanitize(str: String?): String {
        if (str == null) return ""
        val s = stripMarkdown(str)
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            val rep = MAP[c]
            if (rep != null) {
                out.append(rep)
            } else if (c == 10 || c == 9 || (c in 0x20..0x7E) || (c in 0xA1..0xFF)) {
                out.append(ch)
            }
            // everything else (controls, emoji, surrogates, non-Latin-1) is dropped
        }
        var r = out.toString()
        r = TRAILING_WS_BEFORE_NL.replace(r, "\n")
        r = MULTI_SPACE.replace(r, " ")
        r = MANY_NL.replace(r, "\n\n")
        return EDGE_WS.replace(r, "")
    }

    /**
     * Latin-1 normalization only (no markdown stripping, no whitespace changes): known characters are
     * mapped as in [sanitize], anything the watch can't draw is dropped. Used to check that text shown
     * for approval is exactly the text that will be sent.
     */
    fun plainLatin1(str: String): String {
        val out = StringBuilder(str.length)
        for (ch in str) {
            val c = ch.code
            val rep = MAP[c]
            if (rep != null) out.append(rep)
            else if (c == 10 || c == 9 || (c in 0x20..0x7E) || (c in 0xA1..0xFF)) out.append(ch)
        }
        return out.toString()
    }

    private data class CpInfo(val bytes: Int, val units: Int)

    private fun cpInfo(str: String, i: Int): CpInfo {
        val c = str[i].code
        if (c < 0x80) return CpInfo(1, 1)
        if (c < 0x800) return CpInfo(2, 1)
        if (c in 0xD800..0xDBFF && i + 1 < str.length) {
            val d = str[i + 1].code
            if (d in 0xDC00..0xDFFF) return CpInfo(4, 2)
        }
        return CpInfo(3, 1)
    }

    fun utf8Bytes(str: String): Int {
        var n = 0
        var i = 0
        while (i < str.length) {
            val info = cpInfo(str, i)
            n += info.bytes
            i += info.units
        }
        return n
    }

    /** Longest prefix (in UTF-16 units) that fits in maxBytes. */
    private fun prefixUnits(str: String, maxBytes: Int): Int {
        var n = 0
        var i = 0
        while (i < str.length) {
            val info = cpInfo(str, i)
            if (n + info.bytes > maxBytes) break
            n += info.bytes
            i += info.units
        }
        return i
    }

    private val TRAILING_WS = Regex("\\s+$")

    fun truncateBytes(str: String, maxBytes: Int): String {
        if (utf8Bytes(str) <= maxBytes) return str
        if (maxBytes <= 3) return "...".substring(0, maxOf(0, maxBytes))
        val end = prefixUnits(str, maxBytes - 3)
        return TRAILING_WS.replace(str.substring(0, end), "") + "..."
    }

    /**
     * Split into pieces of at most maxBytes UTF-8 bytes. Prefers to break after a
     * space or newline. Concatenating the pieces gives back the original string.
     */
    fun chunk(str: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < str.length) {
            val rest = str.substring(start)
            if (utf8Bytes(rest) <= maxBytes) {
                out.add(rest); break
            }
            var end = prefixUnits(rest, maxBytes)
            if (end <= 0) end = if (rest.length >= 2) 2 else 1
            var cut = end
            for (j in end - 1 downTo 1) {
                val ch = rest[j]
                if (ch == ' ' || ch == '\n') {
                    cut = j + 1; break
                }
            }
            out.add(rest.substring(0, cut))
            start += cut
        }
        return out
    }

    /** Text exactly as the watch receives it: capped answer split into CHUNK payloads. */
    fun watchChunks(answer: String): List<String> = chunk(truncateBytes(answer, MAX_ANSWER_BYTES), CHUNK_BYTES)

    /** One line, sanitized and byte-capped (labels, reminder texts, status lines). */
    fun oneLineCapped(s: String, maxBytes: Int): String =
        truncateBytes(sanitize(s).replace('\n', ' '), maxBytes)
}
