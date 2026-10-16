package com.aaron.glassalarm.alarm

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aaron.glassalarm.data.CalendarRepo
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import java.text.DateFormat
import java.util.Date

/**
 * Persistent foreground service ("Aggressive"/"Balanced" modes).
 * - ContentObserver on the calendar provider -> rescan ~1s after any change (new event = alarm scheduled in seconds)
 * - Screen-on / unlock -> rescan + server sync nudge (catches events added on the web while the phone slept)
 * - Being a foreground service keeps the process alive so OxygenOS is less tempted to kill it.
 */
class WatcherService : Service() {

    companion object {
        @Volatile var running = false

        fun ensureRunning(ctx: Context) {
            val prefs = Prefs(ctx)
            val want = prefs.perfMode.persistentWatcher && CalendarRepo.hasPermission(ctx)
            if (!want) {
                if (running) ctx.stopService(Intent(ctx, WatcherService::class.java))
                return
            }
            if (running) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WatcherService::class.java))
            } catch (e: Exception) {
                EventLog.add(ctx, "watcher start refused: ${e.javaClass.simpleName} (will retry on next heartbeat)")
            }
        }

        fun refreshNotification(ctx: Context) {
            if (!running) return
            runCatching {
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(ctx).notify(Notifications.ID_WATCHER, Notifications.watcher(ctx, statusText(ctx)))
            }
        }

        private fun statusText(ctx: Context): String {
            val p = Prefs(ctx)
            return if (p.nextAlarmAt > 0)
                "Next: ${p.nextAlarmTitle} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.nextAlarmAt))}"
            else "No upcoming alarms"
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val rescan = Runnable { Scheduler.runAsync { Scheduler.reschedule(this, "calendar-changed"); refreshNotification(this) } }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            handler.removeCallbacks(rescan)
            handler.postDelayed(rescan, 1200) // debounce bursts from the sync adapter
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = Prefs(context)
            // When you pick the phone up, quickly pull from the server (rate-limited to once a minute).
            if (System.currentTimeMillis() - prefs.lastSyncRequestAt > 60_000L) {
                Scheduler.runAsync { CalendarRepo.requestSync(context, prefs) }
            }
            handler.removeCallbacks(rescan)
            handler.postDelayed(rescan, 800)
        }
    }

    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            Scheduler.runAsync {
                EventLog.add(app, "Android Auto state changed → ${AndroidAuto.label(AndroidAuto.state(app))}")
                DrivingGate.checkHolds(app, "car-broadcast")
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            Scheduler.runAsync { Heartbeat.beat(this@WatcherService, "watcher-tick"); refreshNotification(this@WatcherService) }
            handler.postDelayed(this, Prefs(this@WatcherService).perfMode.heartbeatMin * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_WATCHER, Notifications.watcher(this, statusText(this)), type)
        } catch (e: Exception) {
            EventLog.add(this, "watcher startForeground failed: ${e.message}")
            stopSelf()
            return
        }
        running = true
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Android Auto announces connect/disconnect with this broadcast (same one the Car App Library listens to).
        // We only use it as a *prompt* to re-query the real state; the broadcast payload itself is never trusted.
        ContextCompat.registerReceiver(
            this, carReceiver, IntentFilter(AndroidAuto.ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED,
        )
        handler.postDelayed(tick, Prefs(this).perfMode.heartbeatMin * 60_000L)
        EventLog.add(this, "watcher started (${Prefs(this).perfMode.label})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { contentResolver.unregisterContentObserver(observer) }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(carReceiver) }
        EventLog.add(this, "watcher stopped")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: make sure alarms are still scheduled and come back.
        Scheduler.rescheduleAsync(this, "task-removed")
        super.onTaskRemoved(rootIntent)
    }
}

/**
 * JobScheduler content-trigger: the OS itself wakes us when the calendar provider changes, even if our
 * process is dead. This is the safety net under the watcher service (and the main path in "Light" mode).
 */
class CalendarChangeJob : JobService() {
    companion object {
        private const val ID = 7001

        fun schedule(ctx: Context, force: Boolean = false) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!force && js.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(ctx, CalendarChangeJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)
                )
                .setTriggerContentUpdateDelay(500)
                .setTriggerContentMaxDelay(3000)
                .build()
            runCatching { js.schedule(job) }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Scheduler.runAsync {
            try {
                Scheduler.reschedule(this, "calendar-changed(job)")
                WatcherService.refreshNotification(this)
            } finally {
                jobFinished(params, false)
                schedule(this, force = true) // content-trigger jobs are one-shot; re-arm
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
