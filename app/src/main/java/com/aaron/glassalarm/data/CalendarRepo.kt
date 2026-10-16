package com.aaron.glassalarm.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val visible: Boolean,
)

data class EventInstance(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
    val location: String?,
    val reminders: List<Int>,
) {
    val instanceKey: String get() = "$eventId@$begin"
}

/** One concrete moment we will alarm for. */
data class AlarmTrigger(
    val instance: EventInstance,
    val triggerAt: Long,
    val minutesBefore: Int,
    val snoozed: Boolean = false,
) {
    val key: String get() = "${instance.instanceKey}#$minutesBefore"
}

/** Lightweight payload handed to the ring service / alarm screen. */
data class RingingAlarm(
    val key: String,
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String,
    val color: Int,
    val minutesBefore: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("eventId", eventId).put("title", title)
        .put("begin", begin).put("end", end).put("allDay", allDay)
        .put("location", location ?: "").put("cal", calendarName)
        .put("color", color).put("minBefore", minutesBefore)

    companion object {
        fun from(t: AlarmTrigger) = RingingAlarm(
            t.key, t.instance.eventId, t.instance.title, t.instance.begin, t.instance.end,
            t.instance.allDay, t.instance.location, t.instance.calendarName, t.instance.color, t.minutesBefore,
        )

        fun listToJson(list: List<RingingAlarm>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<RingingAlarm> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    RingingAlarm(
                        o.getString("key"), o.optLong("eventId"), o.optString("title"),
                        o.optLong("begin"), o.optLong("end"), o.optBoolean("allDay"),
                        o.optString("location").ifBlank { null }, o.optString("cal"),
                        o.optInt("color"), o.optInt("minBefore"),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

object CalendarRepo {

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalendarInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
        )
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "Calendar",
                        accountName = c.getString(2) ?: "Local",
                        accountType = c.getString(3) ?: "",
                        color = c.getInt(4),
                        visible = c.getInt(5) == 1,
                    )
                }
            }
        }.onFailure { EventLog.add(ctx, "calendar query failed: ${it.message}") }
        return out
    }

    /**
     * All event instances overlapping [from, to] in enabled calendars, with their popup reminders.
     * We read events directly, so an event does NOT need a reminder for us to alarm on it.
     */
    fun instances(ctx: Context, from: Long, to: Long, prefs: Prefs, includeDisabledCalendars: Boolean = false): List<EventInstance> {
        if (!hasPermission(ctx)) return emptyList()
        val disabled = prefs.disabledCalendars()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,          // 0
            CalendarContract.Instances.TITLE,             // 1
            CalendarContract.Instances.BEGIN,             // 2
            CalendarContract.Instances.END,               // 3
            CalendarContract.Instances.ALL_DAY,           // 4
            CalendarContract.Instances.CALENDAR_ID,       // 5
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, // 6
            CalendarContract.Instances.DISPLAY_COLOR,     // 7
            CalendarContract.Instances.EVENT_LOCATION,    // 8
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, // 9
            CalendarContract.Instances.STATUS,            // 10
        )
        val raw = mutableListOf<EventInstance>()
        runCatching {
            ctx.contentResolver.query(builder.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val calId = c.getLong(5)
                    if (!includeDisabledCalendars && calId in disabled) continue
                    if (!c.isNull(10) && c.getInt(10) == CalendarContract.Events.STATUS_CANCELED) continue
                    if (prefs.skipDeclined && !c.isNull(9) &&
                        c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    raw += EventInstance(
                        eventId = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(No title)",
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        allDay = c.getInt(4) == 1,
                        calendarId = calId,
                        calendarName = c.getString(6) ?: "",
                        color = c.getInt(7),
                        location = c.getString(8)?.takeIf { it.isNotBlank() },
                        reminders = emptyList(),
                    )
                }
            }
        }.onFailure { EventLog.add(ctx, "instances query failed: ${it.message}") }

        val reminders = reminders(ctx, raw.map { it.eventId }.distinct())
        return raw.map { it.copy(reminders = reminders[it.eventId].orEmpty()) }
    }

    private fun reminders(ctx: Context, eventIds: List<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<Int>>()
        eventIds.chunked(400).forEach { chunk ->
            val sel = "${CalendarContract.Reminders.EVENT_ID} IN (${chunk.joinToString(",")})"
            runCatching {
                ctx.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                    sel, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val method = c.getInt(2)
                        // Popup-style reminders only (default/alert/alarm). Email/SMS reminders are ignored.
                        if (method == CalendarContract.Reminders.METHOD_EMAIL || method == CalendarContract.Reminders.METHOD_SMS) continue
                        var min = c.getInt(1)
                        if (min < 0) min = 10 // "use default"
                        out.getOrPut(c.getLong(0)) { mutableListOf() }.add(min)
                    }
                }
            }
        }
        return out.mapValues { it.value.distinct().sorted() }
    }

    /** Local wall-clock time for an all-day event's alarm (all-day BEGIN is UTC midnight). */
    fun allDayAlarmTime(begin: Long, minuteOfDay: Int): Long {
        val date = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        return date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** All-day BEGIN/END are UTC-midnight; shift to local midnight for display. */
    fun allDayLocalStart(begin: Long): Long =
        Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Nudge the calendar sync adapters to pull from the server now, so events added on the web/another device
     * show up in seconds instead of waiting for the account's own sync schedule.
     */
    fun requestSync(ctx: Context, prefs: Prefs) {
        val accounts = calendars(ctx)
            .filter { it.id !in prefs.disabledCalendars() && it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL && it.accountType.isNotBlank() }
            .map { Account(it.accountName, it.accountType) }
            .distinct()
        accounts.forEach { acc ->
            runCatching {
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(acc, CalendarContract.AUTHORITY, extras)
            }
        }
        prefs.lastSyncRequestAt = System.currentTimeMillis()
    }
}
