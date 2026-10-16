package com.mrarron.wristclaude.phone

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class CalendarInfo(val id: Long, val name: String, val account: String, val primary: Boolean, val writable: Boolean)

data class EventInfo(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?, val calendar: String)

object CalendarRepo {
    fun canRead(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context) = canRead(ctx) &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalendarInfo> {
        if (!canRead(ctx)) return emptyList()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        val out = ArrayList<CalendarInfo>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "Calendar",
                    account = c.getString(2) ?: "",
                    primary = c.getInt(3) == 1,
                    writable = c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return out
    }

    /** The chosen default calendar if it's still writable, else the first writable primary, else any writable. */
    fun defaultCalendar(ctx: Context, chosenId: Long): CalendarInfo? {
        val writable = calendars(ctx).filter { it.writable }
        return writable.firstOrNull { it.id == chosenId } ?: writable.firstOrNull { it.primary } ?: writable.firstOrNull()
    }

    fun events(ctx: Context, start: ZonedDateTime, end: ZonedDateTime, max: Int = 30): List<EventInfo> {
        if (!canRead(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start.toInstant().toEpochMilli())
            ContentUris.appendId(it, end.toInstant().toEpochMilli())
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = ArrayList<EventInfo>()
        ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < max) {
                out += EventInfo(
                    title = c.getString(0) ?: "(no title)",
                    begin = c.getLong(1),
                    end = c.getLong(2),
                    allDay = c.getInt(3) == 1,
                    location = c.getString(4)?.takeIf { it.isNotBlank() },
                    calendar = c.getString(5) ?: "",
                )
            }
        }
        return out
    }

    /** Inserts an event; returns the calendar name it went to, or null. */
    fun insert(
        ctx: Context,
        calendar: CalendarInfo,
        title: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        allDay: Boolean,
        location: String?,
    ): Long? {
        if (!canWrite(ctx)) return null
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            put(CalendarContract.Events.TITLE, title)
            if (allDay) {
                // All-day events are stored as UTC midnight to UTC midnight
                val s = start.toLocalDate().atStartOfDay(ZoneId.of("UTC"))
                val e = maxOf(end.toLocalDate(), start.toLocalDate().plusDays(1)).atStartOfDay(ZoneId.of("UTC"))
                put(CalendarContract.Events.DTSTART, s.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, e.toInstant().toEpochMilli())
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, start.zone.id)
            }
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        return ContentUris.parseId(uri)
    }

    fun toZoned(ms: Long, zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
}
