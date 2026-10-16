package com.mrarron.wristclaude.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Pure helpers for the phone tools (kept free of Android types so they can be unit-tested). */
object ToolFormat {
    /** java.util.Calendar day constants (SUNDAY=1 .. SATURDAY=7) for set_alarm "days". */
    fun alarmDays(days: List<String>): ArrayList<Int> {
        val map = mapOf("sun" to 1, "mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6, "sat" to 7)
        return ArrayList(days.mapNotNull { map[it.trim().lowercase().take(3)] }.distinct().sorted())
    }

    fun hhmm(hour: Int, minute: Int) = TimeUtil.pad2(hour) + ":" + TimeUtil.pad2(minute)

    /** "1 h 30 min", "45 s", "10 min". */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = ArrayList<String>()
        if (h > 0) parts += "$h h"
        if (m > 0) parts += "$m min"
        if (s > 0 && h == 0L) parts += "$s s"
        return parts.joinToString(" ").ifEmpty { "0 s" }
    }

    private fun dayLabel(d: ZonedDateTime): String =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).take(3) + " " +
            d.month.getDisplayName(TextStyle.SHORT, Locale.US).take(3) + " " + d.dayOfMonth

    /** One event line for Claude, e.g. "Tue Oct 14 15:00-16:00 Dentist @ Main St (Work)". */
    fun eventLine(title: String, beginMs: Long, endMs: Long, allDay: Boolean, location: String?, calendar: String?, zone: ZoneId): String {
        val sb = StringBuilder()
        if (allDay) {
            // all-day instants are UTC midnights
            val b = ZonedDateTime.ofInstant(Instant.ofEpochMilli(beginMs), ZoneId.of("UTC"))
            sb.append(dayLabel(b)).append(" all day")
        } else {
            val b = ZonedDateTime.ofInstant(Instant.ofEpochMilli(beginMs), zone)
            val e = ZonedDateTime.ofInstant(Instant.ofEpochMilli(endMs), zone)
            sb.append(dayLabel(b)).append(' ').append(TimeUtil.hhmm(b)).append('-')
            if (e.toLocalDate() != b.toLocalDate()) sb.append(dayLabel(e)).append(' ')
            sb.append(TimeUtil.hhmm(e))
        }
        sb.append(' ').append(title)
        if (!location.isNullOrBlank()) sb.append(" @ ").append(location)
        if (!calendar.isNullOrBlank()) sb.append(" (").append(calendar).append(')')
        return sb.toString()
    }

    data class EventTimes(val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean)

    private val DATE_ONLY = Regex("^\\s*\\d{4}-\\d{2}-\\d{2}\\s*$")

    /**
     * Resolves add_calendar_event times: all-day if asked or if start is a bare date;
     * end = given end, else start + duration_minutes (default 60); end must be after start.
     */
    fun eventTimes(start: String?, end: String?, durationMinutes: Long?, allDay: Boolean?, zone: ZoneId): EventTimes? {
        val s = TimeUtil.parseLocal(start, zone) ?: return null
        val isAllDay = allDay ?: (start != null && DATE_ONLY.matches(start))
        if (isAllDay) {
            val sd = s.toLocalDate().atStartOfDay(zone)
            var ed = TimeUtil.parseLocal(end, zone)?.toLocalDate()?.atStartOfDay(zone)
            if (ed == null || !ed.isAfter(sd)) ed = sd.plusDays(1)
            return EventTimes(sd, ed, true)
        }
        var e = TimeUtil.parseLocal(end, zone)
        if (e == null || !e.isAfter(s)) {
            val mins = (durationMinutes ?: 60L).coerceIn(1L, 24L * 60 * 14)
            e = s.plusMinutes(mins)
        }
        return EventTimes(s, e, false)
    }

    /** get_calendar_events range: end defaults to (and must be after) start; capped at 31 days. */
    fun eventRange(start: String?, end: String?, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime>? {
        val s = TimeUtil.parseLocal(start, zone) ?: return null
        var e = TimeUtil.parseLocal(end, zone)
        if (e == null || !e.isAfter(s)) e = s.plusDays(1)
        if (e.isAfter(s.plusDays(31))) e = s.plusDays(31)
        return s to e
    }

    fun timeLabel(ms: Long, now: ZonedDateTime): String = TimeUtil.formatReminder(ms / 1000, now)

    @Suppress("unused")
    fun dayOfWeekShort(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.US).take(3)
}
