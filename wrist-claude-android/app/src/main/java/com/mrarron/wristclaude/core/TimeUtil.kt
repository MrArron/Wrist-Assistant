package com.mrarron.wristclaude.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Reminder time math and date strings, ported from src/pkjs/claude.js. */
object TimeUtil {
    const val MIN_REMINDER_S = 60L
    const val MAX_REMINDER_S = 7L * 24 * 3600

    private val HHMM = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$")

    /**
     * Unix seconds for a set_reminder input, or null if unusable.
     * [minutesFromNow] may be a Number or numeric String (as Claude sometimes sends).
     */
    fun reminderTime(minutesFromNow: Any?, atLocalTime: String?, now: ZonedDateTime): Long? {
        val nowS = now.toEpochSecond()
        var t: Long? = null
        val mins: Double? = when (minutesFromNow) {
            is Number -> minutesFromNow.toDouble()
            is String -> if (minutesFromNow.isNotEmpty()) minutesFromNow.trim().toDoubleOrNull() ?: Double.NaN else null
            else -> null
        }
        if (mins != null && mins.isFinite()) {
            t = nowS + jsRound(mins * 60)
        } else if (atLocalTime != null) {
            val m = HHMM.find(atLocalTime)
            if (m != null) {
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].toInt()
                if (h <= 23 && mi <= 59) {
                    var target = now.withHour(h).withMinute(mi).withSecond(0).withNano(0)
                    if (target.toEpochSecond() <= nowS) target = target.plusDays(1)
                    t = target.toEpochSecond()
                }
            }
        }
        if (t == null) return null
        if (t < nowS + MIN_REMINDER_S) t = nowS + MIN_REMINDER_S
        if (t > nowS + MAX_REMINDER_S) t = nowS + MAX_REMINDER_S
        return t
    }

    /** JS Math.round semantics (rounds .5 toward +infinity). */
    private fun jsRound(x: Double): Long = kotlin.math.floor(x + 0.5).roundToLong()

    fun pad2(n: Int) = if (n < 10) "0$n" else "$n"

    fun hhmm(d: ZonedDateTime) = pad2(d.hour) + ":" + pad2(d.minute)

    private fun dayShort(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.US).take(3)

    /** "15:30", or "Tue 09:05" when not today. */
    fun formatReminder(unix: Long, now: ZonedDateTime): String {
        val d = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(unix), now.zone)
        var s = hhmm(d)
        if (d.toLocalDate() != now.toLocalDate()) s = dayShort(d.dayOfWeek) + " " + s
        return s
    }

    fun utcOffsetString(d: ZonedDateTime): String {
        var off = d.offset.totalSeconds / 60
        val sign = if (off < 0) "-" else "+"
        off = abs(off)
        return "UTC" + sign + pad2(off / 60) + ":" + pad2(off % 60)
    }

    /** "Wed Oct 14 2026, 12:00 (UTC-04:00)" — same as the JS system prompt line. */
    fun nowLine(now: ZonedDateTime): String {
        val month = now.month.getDisplayName(TextStyle.SHORT, Locale.US).take(3)
        return dayShort(now.dayOfWeek) + " " + month + " " + now.dayOfMonth + " " + now.year + ", " +
            hhmm(now) + " (" + utcOffsetString(now) + ")"
    }

    /** Parse "2026-10-14", "2026-10-14T15:00", "2026-10-14 15:00[:ss]" (local time) or with offset. */
    fun parseLocal(s: String?, zone: ZoneId): ZonedDateTime? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        runCatching { return java.time.OffsetDateTime.parse(t).atZoneSameInstant(zone) }
        runCatching { return ZonedDateTime.parse(t).withZoneSameInstant(zone) }
        val norm = t.replace(' ', 'T')
        runCatching { return LocalDateTime.parse(norm).atZone(zone) }
        runCatching { return java.time.LocalDate.parse(t).atStartOfDay(zone) }
        return null
    }
}
