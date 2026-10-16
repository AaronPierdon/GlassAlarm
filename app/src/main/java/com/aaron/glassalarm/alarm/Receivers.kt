package com.aaron.glassalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aaron.glassalarm.data.CalendarRepo
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.RingingAlarm
import com.aaron.glassalarm.data.Weather

/** Receives our own AlarmManager alarms. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                // Synchronous on purpose: the alarm-clock FGS/activity-start allowance is only valid briefly.
                val n = runCatching { Scheduler.fireDue(ctx, "alarm") }
                    .onFailure { EventLog.add(ctx, "fireDue crashed: $it") }
                    .getOrDefault(0)
                if (n == 0) EventLog.add(ctx, "alarm woke us but nothing was due (event moved/deleted?)")
                val pending = goAsync()
                Scheduler.runAsync {
                    try { Scheduler.reschedule(ctx, "after-fire") } finally { pending.finish() }
                }
            }

            Scheduler.ACTION_TEST -> {
                val now = System.currentTimeMillis()
                EventLog.add(ctx, "TEST alarm fired")
                val test = listOf(
                        RingingAlarm(
                            key = "test#$now", eventId = -1, title = "Test alarm — it works!",
                            begin = now + 10 * 60_000L, end = now + 40 * 60_000L, allDay = false,
                            location = "Lock-screen wake test", calendarName = "Glass Alarm",
                            color = 0xFFFF6F91.toInt(), minutesBefore = 10,
                        )
                    )
                // Tests go through the same Android Auto gate, so you can verify it in the car.
                val allowed = DrivingGate.filter(ctx, test, "test")
                if (allowed.isNotEmpty()) AlarmRingService.start(ctx, allowed)
            }

            DrivingGate.ACTION_CHECK_HOLDS -> DrivingGate.checkHolds(ctx, "hold-check")

            Scheduler.ACTION_HEARTBEAT -> {
                val pending = goAsync()
                Scheduler.runAsync {
                    try { Heartbeat.beat(ctx, "heartbeat") } finally { pending.finish() }
                }
            }
        }
    }
}

object Heartbeat {
    /** Shared periodic work: catch missed alarms, rescan, sync nudge, keep watcher alive, refresh weather. */
    fun beat(ctx: Context, source: String) {
        val prefs = Prefs(ctx)
        runCatching { Scheduler.fireDue(ctx, source) }
        runCatching { DrivingGate.checkHolds(ctx, source) }
        val now = System.currentTimeMillis()
        if (now - prefs.lastSyncRequestAt > prefs.perfMode.syncMin * 60_000L) {
            CalendarRepo.requestSync(ctx, prefs)
        }
        Scheduler.reschedule(ctx, source)
        CalendarChangeJob.schedule(ctx)
        WatcherService.ensureRunning(ctx)
        if (prefs.showWeather) runCatching { Weather.refreshIfStale(ctx, maxAgeMinutes = 60) }
    }
}

/** System broadcasts: boot, time change, app update, and the calendar provider's own reminder/changed broadcasts. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action ?: return
        when (action) {
            "android.intent.action.EVENT_REMINDER" -> {
                // Independent second path: Google Calendar's own reminder fired. Ring anything that's due right now.
                runCatching { Scheduler.fireDue(ctx, "calendar-reminder") }
                Scheduler.rescheduleAsync(ctx, "calendar-reminder")
            }
            Intent.ACTION_PROVIDER_CHANGED -> Scheduler.rescheduleAsync(ctx, "provider-changed")
            else -> {
                EventLog.add(ctx, "system event: ${action.substringAfterLast('.')}")
                val pending = goAsync()
                Scheduler.runAsync {
                    try { Heartbeat.beat(ctx, action.substringAfterLast('.').lowercase()) } finally { pending.finish() }
                }
            }
        }
    }
}
