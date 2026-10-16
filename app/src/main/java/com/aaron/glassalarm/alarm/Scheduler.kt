package com.aaron.glassalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.aaron.glassalarm.data.AlarmTrigger
import com.aaron.glassalarm.data.AlertMode
import com.aaron.glassalarm.data.CalendarRepo
import com.aaron.glassalarm.data.EventInstance
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.RingingAlarm
import com.aaron.glassalarm.data.TriggerMode
import com.aaron.glassalarm.ui.MainActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** One row of the "Upcoming" list. */
data class UpcomingItem(
    val instance: EventInstance,
    val triggers: List<AlarmTrigger>,
    val mode: AlertMode,
)

object Scheduler {
    const val ACTION_FIRE = "com.aaron.glassalarm.action.FIRE"
    const val ACTION_HEARTBEAT = "com.aaron.glassalarm.action.HEARTBEAT"
    const val ACTION_TEST = "com.aaron.glassalarm.action.TEST"

    private const val RC_FIRE = 1001
    private const val RC_HEARTBEAT = 1002
    private const val RC_SHOW = 1003
    private const val RC_TEST = 1004

    private val executor = Executors.newSingleThreadExecutor()

    // ----------------------------------------------------------------------------------------
    // Computing triggers
    // ----------------------------------------------------------------------------------------

    fun offsetsFor(inst: EventInstance, prefs: Prefs): List<Int> {
        if (inst.allDay) return listOf(0)
        val rem = inst.reminders
        return when (prefs.triggerMode) {
            TriggerMode.START -> listOf(prefs.defaultLeadMinutes)
            TriggerMode.REMINDERS ->
                if (rem.isNotEmpty()) rem
                else if (prefs.fallbackWhenNoReminder) listOf(prefs.defaultLeadMinutes)
                else emptyList()
            TriggerMode.BOTH -> (rem + prefs.defaultLeadMinutes).distinct()
        }.sortedDescending()
    }

    private fun triggerTime(inst: EventInstance, minutesBefore: Int, prefs: Prefs): Long =
        if (inst.allDay) CalendarRepo.allDayAlarmTime(inst.begin, prefs.allDayMinuteOfDay)
        else inst.begin - minutesBefore * 60_000L

    /** Every pending trigger (not yet fired, not disabled), soonest first. */
    fun computeTriggers(ctx: Context, prefs: Prefs, now: Long = System.currentTimeMillis()): List<AlarmTrigger> {
        val maxLate = prefs.maxLateMinutes * 60_000L
        // Look back a day so reminders for already-started-but-not-yet-reminded instances and snoozes are found.
        val from = now - 26 * 3600_000L
        val to = now + prefs.lookaheadDays * 86400_000L + 26 * 3600_000L
        val fired = prefs.firedMap()
        val snoozes = prefs.snoozeMap()
        val out = mutableListOf<AlarmTrigger>()
        for (inst in CalendarRepo.instances(ctx, from, to, prefs)) {
            if (inst.allDay && !prefs.includeAllDay) continue
            if (prefs.instanceMode(inst.instanceKey) == AlertMode.OFF) continue
            for (m in offsetsFor(inst, prefs)) {
                val base = AlarmTrigger(inst, triggerTime(inst, m, prefs), m)
                val snoozedUntil = snoozes[base.key]
                val t = if (snoozedUntil != null) base.copy(triggerAt = snoozedUntil, snoozed = true) else base
                if (snoozedUntil == null && fired.containsKey(t.key)) continue
                if (t.triggerAt < now - maxLate) continue
                if (t.triggerAt > now + prefs.lookaheadDays * 86400_000L) continue
                out += t
            }
        }
        return out.sortedBy { it.triggerAt }
    }

    /** For the UI: every instance in the window (including OFF ones) with its future triggers. */
    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()): List<UpcomingItem> {
        val prefs = Prefs(ctx)
        val triggers = computeTriggers(ctx, prefs, now).groupBy { it.instance.instanceKey }
        val to = now + prefs.lookaheadDays * 86400_000L
        return CalendarRepo.instances(ctx, now - 3600_000L, to, prefs)
            .filter { it.end >= now || triggers.containsKey(it.instanceKey) }
            .map { UpcomingItem(it, triggers[it.instanceKey].orEmpty(), prefs.instanceMode(it.instanceKey)) }
            .sortedBy { if (it.instance.allDay) CalendarRepo.allDayLocalStart(it.instance.begin) else it.instance.begin }
    }

    // ----------------------------------------------------------------------------------------
    // Scheduling
    // ----------------------------------------------------------------------------------------

    /** Run work off the main thread on the single scheduler thread. */
    fun runAsync(block: () -> Unit) {
        executor.execute { runCatching(block).onFailure { android.util.Log.e("GlassAlarm", "async failed", it) } }
    }

    fun rescheduleAsync(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        executor.execute { runCatching { reschedule(app, reason) }.onFailure { EventLog.add(app, "reschedule crashed: $it") } }
    }

    @Synchronized
    fun reschedule(ctx: Context, reason: String) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        prefs.prune(now)
        val triggers = computeTriggers(ctx, prefs, now)
        val next = triggers.firstOrNull()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val firePi = firePendingIntent(ctx)

        if (next == null) {
            am.cancel(firePi)
            if (prefs.nextAlarmAt != 0L) EventLog.add(ctx, "[$reason] no upcoming alarms")
            prefs.nextAlarmAt = 0L
            prefs.nextAlarmTitle = ""
        } else {
            val at = maxOf(next.triggerAt, now + 1000)
            val changed = at != prefs.nextAlarmAt
            setAlarmClock(ctx, am, at, firePi)
            prefs.nextAlarmAt = at
            prefs.nextAlarmTitle = next.instance.title
            if (changed) {
                EventLog.add(ctx, "[$reason] next: '${next.instance.title}' @ ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))} (${triggers.size} queued)")
            }
        }
        prefs.lastRescanAt = now
        scheduleHeartbeat(ctx, prefs)
        HealthCheck.run(ctx, quiet = true)
    }

    private fun setAlarmClock(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                // setAlarmClock = highest-priority alarm type. Exempt from Doze, shows the alarm icon,
                // and grants a temporary allowance to start a foreground service + activity when it fires.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPendingIntent(ctx)), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                EventLog.add(ctx, "WARNING: exact alarms not allowed; using inexact alarm")
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            EventLog.add(ctx, "setAlarmClock SecurityException: ${e.message}")
        }
    }

    fun scheduleHeartbeat(ctx: Context, prefs: Prefs = Prefs(ctx)) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        var minutes = prefs.perfMode.heartbeatMin.toLong()
        // "Internal sleep": when the screen is off and nothing is due soon, back off 4x.
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val soon = prefs.nextAlarmAt != 0L && prefs.nextAlarmAt - System.currentTimeMillis() < 2 * 3600_000L
        if (!pm.isInteractive && !soon) minutes *= 4
        val pi = PendingIntent.getBroadcast(
            ctx, RC_HEARTBEAT,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_HEARTBEAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, pi)
    }

    fun scheduleTest(ctx: Context, delaySeconds: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, RC_TEST,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarmClock(ctx, am, System.currentTimeMillis() + delaySeconds * 1000L, pi)
        EventLog.add(ctx, "test alarm scheduled in ${delaySeconds}s")
    }

    private fun firePendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RC_FIRE,
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showPendingIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, RC_SHOW,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ----------------------------------------------------------------------------------------
    // Firing
    // ----------------------------------------------------------------------------------------

    /**
     * Called (synchronously, inside the alarm broadcast) when the alarm goes off — or from any other path
     * (calendar EVENT_REMINDER broadcast, heartbeat) that notices something is due. Idempotent thanks to the fired map.
     */
    @Synchronized
    fun fireDue(ctx: Context, source: String): Int {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val due = computeTriggers(ctx, prefs, now).filter { it.triggerAt <= now + 15_000 }
        if (due.isEmpty()) return 0
        // Collapse multiple reminders of the same instance into one ring.
        val byInstance = due.groupBy { it.instance.instanceKey }.map { (_, ts) -> ts.minByOrNull { it.minutesBefore }!! }
        prefs.markFired(due.map { it.key }, now)
        prefs.clearSnoozes(due.map { it.key })

        val alarms = byInstance.filter { prefs.instanceMode(it.instance.instanceKey) == AlertMode.ALARM }
        val notifies = byInstance.filter { prefs.instanceMode(it.instance.instanceKey) == AlertMode.NOTIFY }
        EventLog.add(ctx, "[$source] FIRE ${alarms.size} alarm(s), ${notifies.size} notify: ${byInstance.joinToString { it.instance.title }}")

        notifies.forEach { Notifications.postReminder(ctx, it) }
        if (alarms.isNotEmpty()) {
            // Android Auto gate: checked fresh, right now, every time.
            val allowed = DrivingGate.filter(ctx, alarms.map { RingingAlarm.from(it) }, source)
            if (allowed.isNotEmpty()) AlarmRingService.start(ctx, allowed)
        }
        return byInstance.size
    }
}
