package com.aaron.glassalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aaron.glassalarm.data.CarBehavior
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.RingingAlarm

/**
 * The ONE source of truth for "am I actively using Android Auto right now?".
 *
 * This is Google's official Car App Library "CarConnection" signal. The Android Auto app publishes a content
 * provider (authority "androidx.car.app.connection") whose single value is the live projection state:
 * 0 = not connected, 1 = Android Automotive (native), 2 = PROJECTING (Android Auto running on the car screen).
 * It flips to 2 when the car display session starts and back to 0 when it ends (unplug / Bluetooth-Wi-Fi drop /
 * car off). It is not "is the Android Auto app process alive".
 *
 * We do exactly what androidx.car.app.connection.CarConnection does internally, but synchronously and FRESH on
 * every alarm — nothing is cached, so a stale value can never get stuck. Any failure reads as "not connected"
 * (fail open: an error never silently eats an alarm).
 */
object AndroidAuto {
    private const val AUTHORITY = "androidx.car.app.connection"
    private const val COLUMN = "CarConnectionState" // CarConnection.CAR_CONNECTION_STATE
    const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

    const val UNAVAILABLE = -1
    const val NOT_CONNECTED = 0
    const val NATIVE = 1
    const val PROJECTION = 2

    private val uri: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).build()

    fun state(ctx: Context): Int = try {
        ctx.contentResolver.query(uri, arrayOf(COLUMN), null, null, null)?.use { c ->
            val col = c.getColumnIndex(COLUMN)
            if (col < 0 || !c.moveToFirst()) NOT_CONNECTED else c.getInt(col)
        } ?: UNAVAILABLE // provider missing (Android Auto not installed / not visible)
    } catch (e: Exception) {
        UNAVAILABLE
    }

    fun isProjecting(ctx: Context) = state(ctx) == PROJECTION

    fun label(state: Int) = when (state) {
        PROJECTION -> "Connected — projecting to car screen"
        NATIVE -> "Android Automotive (built-in car OS)"
        NOT_CONNECTED -> "Not connected"
        else -> "Unavailable (Android Auto not installed?)"
    }
}

/**
 * Decides what happens to an alarm that comes due while you're driving.
 *  RING   – ignore Android Auto
 *  SILENT – no screen, no sound: a silent notification only
 *  HOLD   – silent notification now, then ring as soon as Android Auto disconnects (if the event hasn't ended)
 */
object DrivingGate {
    const val ACTION_CHECK_HOLDS = "com.aaron.glassalarm.action.CHECK_HOLDS"
    private const val RC_HOLD_CHECK = 1005
    private const val MAX_HOLD_MS = 4 * 3600_000L

    /** Returns the alarms that are allowed to ring right now; the rest are silenced/held. */
    fun filter(ctx: Context, alarms: List<RingingAlarm>, source: String): List<RingingAlarm> {
        if (alarms.isEmpty()) return alarms
        val prefs = Prefs(ctx)
        if (prefs.carBehavior == CarBehavior.RING) return alarms
        val st = AndroidAuto.state(ctx)
        if (st != AndroidAuto.PROJECTION) return alarms

        EventLog.add(ctx, "[$source] Android Auto is projecting → ${prefs.carBehavior.name}: ${alarms.joinToString { it.title }}")
        if (prefs.carBehavior == CarBehavior.HOLD) {
            prefs.addHolds(alarms)
            scheduleHoldCheck(ctx)
        }
        Notifications.postDriving(ctx, prefs.holds().ifEmpty { alarms }, held = prefs.carBehavior == CarBehavior.HOLD)
        return emptyList()
    }

    /**
     * Release held alarms if Android Auto is no longer projecting. Safe to call from anywhere, any time.
     * Expired holds (event already over, or held > 4h) are dropped so nothing can get stuck.
     */
    @Synchronized
    fun checkHolds(ctx: Context, source: String) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val all = prefs.holds()
        if (all.isEmpty()) return
        val live = all.filter { a ->
            val end = if (a.allDay) a.end else maxOf(a.end, a.begin)
            end > now && now - prefs.holdStartedAt(a.key) < MAX_HOLD_MS
        }
        if (live.size != all.size) EventLog.add(ctx, "[$source] dropped ${all.size - live.size} expired held alarm(s)")

        if (live.isEmpty()) {
            prefs.clearHolds()
            Notifications.cancelDriving(ctx)
            return
        }
        if (prefs.carBehavior != CarBehavior.HOLD) { // user changed the setting mid-hold
            prefs.clearHolds(); Notifications.cancelDriving(ctx); return
        }
        if (AndroidAuto.isProjecting(ctx)) {
            prefs.setHolds(live)
            scheduleHoldCheck(ctx)
            return
        }
        EventLog.add(ctx, "[$source] Android Auto disconnected → releasing ${live.size} held alarm(s)")
        prefs.clearHolds()
        Notifications.cancelDriving(ctx)
        AlarmRingService.start(ctx, live)
    }

    /** Re-check about once a minute while anything is held (screen is usually on/charging while driving). */
    private fun scheduleHoldCheck(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, RC_HOLD_CHECK,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CHECK_HOLDS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + 60_000L
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
