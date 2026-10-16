package com.aaron.glassalarm.alarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.RingingAlarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that owns a ringing alarm:
 *  1. startForeground() with a CATEGORY_ALARM notification that carries a full-screen intent
 *  2. grabs a screen-waking wake lock (belt and braces for OxygenOS)
 *  3. launches AlarmActivity directly (allowed because we hold "Display over other apps")
 *  4. plays the alarm sound on the ALARM stream (ignores silent mode) + vibrates
 */
class AlarmRingService : Service() {

    companion object {
        const val ACTION_START = "com.aaron.glassalarm.ring.START"
        const val ACTION_DISMISS = "com.aaron.glassalarm.ring.DISMISS"
        const val ACTION_SNOOZE = "com.aaron.glassalarm.ring.SNOOZE"
        const val EXTRA_ALARMS = "alarms"
        const val EXTRA_SNOOZE_MIN = "snoozeMin"

        private val _ringing = MutableStateFlow<List<RingingAlarm>>(emptyList())
        /** What's ringing right now; the alarm screen closes itself when this goes empty. */
        val ringing: StateFlow<List<RingingAlarm>> = _ringing

        fun start(ctx: Context, alarms: List<RingingAlarm>) {
            val json = RingingAlarm.listToJson(alarms)
            Prefs(ctx).ringingJson = json
            val i = Intent(ctx, AlarmRingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ALARMS, json)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Foreground-service start was refused. Fall back to a loud full-screen notification + direct launch.
                EventLog.add(ctx, "FGS start refused (${e.javaClass.simpleName}); using fallback notification")
                Notifications.postFallbackAlarm(ctx, alarms)
                AlarmLauncher.launch(ctx, "fallback")
            }
        }

        fun dismiss(ctx: Context) {
            ctx.startService(Intent(ctx, AlarmRingService::class.java).setAction(ACTION_DISMISS))
        }

        fun snooze(ctx: Context, minutes: Int) {
            ctx.startService(
                Intent(ctx, AlarmRingService::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_SNOOZE_MIN, minutes)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volume = 0.15f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val alarms = RingingAlarm.listFromJson(intent.getStringExtra(EXTRA_ALARMS))
                    .ifEmpty { RingingAlarm.listFromJson(Prefs(this).ringingJson) }
                if (alarms.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                // If something is already ringing, merge.
                val merged = (_ringing.value + alarms).distinctBy { it.key }
                _ringing.value = merged
                Prefs(this).ringingJson = RingingAlarm.listToJson(merged)
                goForeground(Notifications.buildAlarm(this, merged))
                ring()
            }
            ACTION_SNOOZE -> {
                val min = intent.getIntExtra(EXTRA_SNOOZE_MIN, Prefs(this).snoozeMinutes)
                val keys = _ringing.value.map { it.key }.ifEmpty { RingingAlarm.listFromJson(Prefs(this).ringingJson).map { it.key } }
                val until = System.currentTimeMillis() + min * 60_000L
                Prefs(this).snooze(keys.filterNot { it.startsWith("test#") }, until)
                EventLog.add(this, "snoozed ${keys.size} for $min min")
                if (keys.any { it.startsWith("test#") }) Scheduler.scheduleTest(this, min * 60)
                finish()
                Scheduler.rescheduleAsync(this, "snooze")
            }
            ACTION_DISMISS -> {
                EventLog.add(this, "dismissed")
                finish()
            }
            else -> if (_ringing.value.isEmpty()) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, Notifications.ID_ALARM, n, type)
        } catch (e: Exception) {
            EventLog.add(this, "startForeground(mediaPlayback) failed: ${e.javaClass.simpleName}; retrying systemExempted")
            try {
                val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
                ServiceCompat.startForeground(this, Notifications.ID_ALARM, n, type)
            } catch (e2: Exception) {
                EventLog.add(this, "startForeground failed again: ${e2.message}")
                runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_ALARM, n) }
            }
        }
    }

    private fun ring() {
        val prefs = Prefs(this)
        wakeScreen()
        AlarmLauncher.launch(this, "service")
        // Re-launch once more after a moment: OxygenOS sometimes swallows the first launch while the keyguard animates.
        handler.postDelayed({ if (_ringing.value.isNotEmpty() && !AlarmActivity.isVisible) AlarmLauncher.launch(this, "retry") }, 1500)
        handler.postDelayed({ if (_ringing.value.isNotEmpty() && !AlarmActivity.isVisible) AlarmLauncher.launch(this, "retry2") }, 4000)
        startSound(prefs)
        if (prefs.vibrate) startVibration()
        handler.removeCallbacks(autoSilence)
        handler.postDelayed(autoSilence, prefs.autoSilenceMinutes * 60_000L)
        handler.removeCallbacks(carWatch)
        handler.postDelayed(carWatch, 5_000L)
    }

    /** If you start driving while it's ringing, stop ringing (and hold/silence it per your setting). */
    private val carWatch = object : Runnable {
        override fun run() {
            if (_ringing.value.isEmpty()) return
            val ctx = this@AlarmRingService
            if (Prefs(ctx).carBehavior == com.aaron.glassalarm.data.CarBehavior.RING) return
            Scheduler.runAsync {
                val projecting = AndroidAuto.isProjecting(ctx)
                handler.post {
                    if (projecting && _ringing.value.isNotEmpty()) {
                        val current = _ringing.value.filterNot { it.key == "preview" }
                        EventLog.add(ctx, "Android Auto connected while ringing → silencing")
                        finish()
                        Scheduler.runAsync { DrivingGate.filter(ctx, current, "while-ringing") }
                    } else if (_ringing.value.isNotEmpty()) {
                        handler.postDelayed(this, 5_000L)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        runCatching {
            screenLock?.let { if (it.isHeld) it.release() }
            // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP physically turns the panel on (needs "Turn screen on" access on 14+).
            screenLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "GlassAlarm:screen",
            ).apply { acquire(60_000L) }
        }.onFailure { EventLog.add(this, "screen wake lock failed: ${it.message}") }
        runCatching {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassAlarm:ring").apply {
                    acquire(Prefs(this@AlarmRingService).autoSilenceMinutes * 60_000L + 60_000L)
                }
            }
        }
    }

    private fun alarmUri(prefs: Prefs): Uri =
        prefs.ringtoneUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun startSound(prefs: Prefs) {
        if (player != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        }
        val uris = listOf(alarmUri(prefs), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        for (uri in uris) {
            val mp = MediaPlayer()
            val ok = runCatching {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                volume = if (prefs.crescendo) 0.15f else 1f
                mp.setVolume(volume, volume)
                mp.prepare()
                mp.start()
            }.isSuccess
            if (ok) { player = mp; break } else runCatching { mp.release() }
        }
        if (player == null) EventLog.add(this, "could not play any alarm sound")
        if (prefs.crescendo) handler.post(rampUp)
    }

    private val rampUp = object : Runnable {
        override fun run() {
            val p = player ?: return
            volume = (volume + 0.05f).coerceAtMost(1f)
            runCatching { p.setVolume(volume, volume) }
            if (volume < 1f) handler.postDelayed(this, 1500)
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else getSystemService(Vibrator::class.java)
        vibrator = v
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 1200), 0), attrs) }
    }

    private val autoSilence = Runnable {
        EventLog.add(this, "auto-silenced after ${Prefs(this).autoSilenceMinutes} min")
        val missed = _ringing.value
        finish()
        Notifications.postMissed(this, missed)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        runCatching { focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }
        runCatching { screenLock?.let { if (it.isHeld) it.release() } }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        _ringing.value = emptyList()
        Prefs(this).ringingJson = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(Notifications.ID_ALARM)
        NotificationManagerCompat.from(this).cancel(Notifications.ID_FALLBACK)
        stopSelf()
    }

    override fun onDestroy() {
        if (_ringing.value.isNotEmpty()) {
            // Killed while ringing (shouldn't happen) — make sure the user still sees something.
            runCatching { player?.release() }
            runCatching { vibrator?.cancel() }
        }
        super.onDestroy()
    }
}

/** Starts the lock-screen alarm activity directly. */
object AlarmLauncher {
    fun launch(ctx: Context, why: String) {
        val i = Intent(ctx, AlarmActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        try {
            ctx.startActivity(i)
            EventLog.add(ctx, "alarm screen launch requested ($why)")
        } catch (e: Exception) {
            EventLog.add(ctx, "alarm screen launch blocked ($why): ${e.javaClass.simpleName}")
        }
    }
}
