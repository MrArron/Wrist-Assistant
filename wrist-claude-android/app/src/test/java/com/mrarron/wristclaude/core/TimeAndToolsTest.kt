package com.mrarron.wristclaude.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeAndToolsTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 14, 12, 0, 30, 0, zone)

    @Test
    fun reminderRelativeAndClamps() {
        val n = now.toEpochSecond()
        assertEquals(n + 1200, TimeUtil.reminderTime(20.0, null, now))
        assertEquals(n + 60, TimeUtil.reminderTime(0.1, null, now)) // min 60 s
        assertEquals(n + 7 * 24 * 3600, TimeUtil.reminderTime(1e7, null, now)) // max 7 days
        assertEquals(n + 900, TimeUtil.reminderTime("15", null, now))
        assertEquals(n + 90, TimeUtil.reminderTime(1.5, null, now))
    }

    @Test
    fun reminderAtLocalTimeRollsToTomorrow() {
        val t = TimeUtil.reminderTime(null, "09:00", now)!!
        val d = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(t), zone)
        assertEquals(15, d.dayOfMonth); assertEquals(9, d.hour); assertEquals(0, d.minute)
        assertEquals("Thu 09:00", TimeUtil.formatReminder(t, now))
        val today = TimeUtil.reminderTime(null, "18:45", now)!!
        assertEquals("18:45", TimeUtil.formatReminder(today, now))
        assertNull(TimeUtil.reminderTime(null, "25:00", now))
        assertNull(TimeUtil.reminderTime(null, "6pm", now))
        assertNull(TimeUtil.reminderTime(null, null, now))
    }

    @Test
    fun parseLocalForms() {
        assertEquals(15, TimeUtil.parseLocal("2026-10-14T15:30", zone)!!.hour)
        assertEquals(15, TimeUtil.parseLocal("2026-10-14 15:30:00", zone)!!.hour)
        assertEquals(0, TimeUtil.parseLocal("2026-10-14", zone)!!.hour)
        assertEquals(11, TimeUtil.parseLocal("2026-10-14T15:30:00Z", zone)!!.hour) // UTC -> EDT
        assertNull(TimeUtil.parseLocal("tomorrow", zone))
    }

    @Test
    fun alarmDaysAndDurations() {
        assertEquals(arrayListOf(2, 4, 6), ToolFormat.alarmDays(listOf("fri", "mon", "Wed", "mon", "xyz")))
        assertEquals(arrayListOf(1, 7), ToolFormat.alarmDays(listOf("sunday", "sat")))
        assertEquals("10 min", ToolFormat.duration(600))
        assertEquals("1 h 30 min", ToolFormat.duration(5400))
        assertEquals("1 min 5 s", ToolFormat.duration(65))
        assertEquals("06:05", ToolFormat.hhmm(6, 5))
    }

    @Test
    fun eventTimes() {
        val t = ToolFormat.eventTimes("2026-10-15T15:00", null, null, null, zone)!!
        assertFalse(t.allDay); assertEquals(16, t.end.hour)
        val t2 = ToolFormat.eventTimes("2026-10-15T15:00", "2026-10-15T15:20", 90, false, zone)!!
        assertEquals(20, t2.end.minute)
        val t3 = ToolFormat.eventTimes("2026-10-15T15:00", "2026-10-15T14:00", 30, null, zone)!! // end before start -> duration
        assertEquals(30, t3.end.minute)
        val ad = ToolFormat.eventTimes("2026-10-15", null, null, null, zone)!!
        assertTrue(ad.allDay); assertEquals(16, ad.end.dayOfMonth)
        assertNull(ToolFormat.eventTimes("soon", null, null, null, zone))
        val (s, e) = ToolFormat.eventRange("2026-10-14", "2027-01-01", zone)!!
        assertEquals(s.plusDays(31), e)
    }

    @Test
    fun eventLineFormat() {
        val b = ZonedDateTime.of(2026, 10, 14, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val e = b + 3_600_000
        assertEquals("Wed Oct 14 15:00-16:00 Dentist @ Main St (Work)", ToolFormat.eventLine("Dentist", b, e, false, "Main St", "Work", zone))
        val utcMidnight = java.time.LocalDate.of(2026, 10, 15).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("Thu Oct 15 all day Holiday", ToolFormat.eventLine("Holiday", utcMidnight, utcMidnight + 86_400_000, true, null, null, zone))
    }

    @Test
    fun recipients() {
        assertEquals("sam@example.com", Recipients.cleanTo("email", " sam@example.com "))
        assertNull(Recipients.cleanTo("email", "Sam"))
        assertEquals("+1 (321) 555-0100", Recipients.cleanTo("text", "+1 (321) 555-0100"))
        assertNull(Recipients.cleanTo("text", "Mom"))
        assertNull(Recipients.cleanTo("text", "sam@example.com"))
        assertNull(Recipients.cleanTo("text", null))
    }

    @Test
    fun confirmTextCappedAndLatin1() {
        val t = Recipients.confirmText("Reply to Mom in Messages:", "“I'll be there” 😀 " + "x".repeat(400))
        assertTrue(TextUtil.utf8Bytes(t) <= 300)
        assertTrue(t.startsWith("Reply to Mom in Messages:\n\"I'll be there\""))
        assertTrue(t.endsWith("..."))
    }

    @Test
    fun settingsValidateLikeJs() {
        val s = AppSettings.validate(
            AppSettings(
                model = "gpt", length = "huge", units = "kelvin", city = " Springfield\nIL ",
                quickPrompts = List(7) { QuickPrompt("Label “$it” that is way too long here", "Prompt $it") } + QuickPrompt("", "x"),
                speechRate = 9f,
            )
        )
        assertEquals(AppSettings.DEFAULT_MODEL, s.model)
        assertEquals("short", s.length)
        assertEquals("imperial", s.units)
        assertEquals("Springfield IL", s.city)
        assertEquals(5, s.quickPrompts.size)
        s.quickPrompts.forEach { assertTrue(TextUtil.utf8Bytes(it.label) <= 24) }
        assertEquals(2.0f, s.speechRate)
        val round = AppSettings.fromJson(s.toJson())
        assertEquals(s, round)
        assertNotNull(AppSettings.fromJson(null).quickPrompts.firstOrNull())
        assertEquals("Fun fact\nMotivate me\nWord of the day", AppSettings().quickNames())
    }
}
