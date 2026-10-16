package com.aaron.glassalarm.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aaron.glassalarm.R
import com.aaron.glassalarm.data.AlarmTrigger
import com.aaron.glassalarm.data.RingingAlarm
import com.aaron.glassalarm.ui.MainActivity
import java.text.DateFormat
import java.util.Date

@SuppressLint("MissingPermission")
object Notifications {
    // Channel ids are versioned: once a user (or OxygenOS) downgrades a channel, only a new id resets it.
    const val CH_ALARM = "alarm_v2"
    const val CH_FALLBACK = "alarm_fallback_v2"
    const val CH_REMINDER = "reminder_v1"
    const val CH_WATCHER = "watcher_v1"
    const val CH_HEALTH = "health_v1"
    const val CH_DRIVING = "driving_v1"

    const val ID_ALARM = 42
    const val ID_FALLBACK = 43
    const val ID_WATCHER = 44
    const val ID_HEALTH = 45
    const val ID_MISSED = 46
    const val ID_DRIVING = 47

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Main alarm channel: max importance, silent (the service plays sound itself on the ALARM stream),
        // visible in full on the lock screen, bypasses Do Not Disturb when we have policy access.
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms (lock screen)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Full-screen calendar alarms. Keep this at the highest level, with lock screen + pop-up enabled."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            setShowBadge(false)
        })

        // Fallback channel that makes its own noise, used only if the foreground service could not start.
        nm.createNotificationChannel(NotificationChannel(CH_FALLBACK, "Alarms (fallback)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Used only if the alarm service is blocked by the system."
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })

        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "Event notifications", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Events you set to 'notify' instead of 'alarm'."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })

        nm.createNotificationChannel(NotificationChannel(CH_WATCHER, "Calendar watcher", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Persistent notification that keeps the calendar watcher alive. You can minimize it."
            setShowBadge(false)
        })

        nm.createNotificationChannel(NotificationChannel(CH_DRIVING, "Held while driving", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Silent note for alarms that came due while Android Auto was on."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })

        nm.createNotificationChannel(NotificationChannel(CH_HEALTH, "Alarm health warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Tells you when the phone revoked something alarms need (e.g. battery optimization got turned back on)."
        })
    }

    private fun alarmScreenIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 2001,
        Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(ctx: Context, action: String, rc: Int): PendingIntent = PendingIntent.getService(
        ctx, rc, Intent(ctx, AlarmRingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 2002, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun timeText(a: RingingAlarm): String =
        if (a.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.begin))

    fun buildAlarm(ctx: Context, alarms: List<RingingAlarm>, channel: String = CH_ALARM): Notification {
        val first = alarms.first()
        val title = if (alarms.size == 1) first.title else "${first.title} +${alarms.size - 1} more"
        val text = buildString {
            append(timeText(first))
            first.location?.let { append(" · ").append(it) }
        }
        return NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(first.color)
            .setShowWhen(true)
            .setWhen(first.begin)
            .setContentIntent(alarmScreenIntent(ctx))
            .setFullScreenIntent(alarmScreenIntent(ctx), true)
            .addAction(0, "Snooze", serviceAction(ctx, AlarmRingService.ACTION_SNOOZE, 2003))
            .addAction(0, "Dismiss", serviceAction(ctx, AlarmRingService.ACTION_DISMISS, 2004))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    fun postFallbackAlarm(ctx: Context, alarms: List<RingingAlarm>) {
        runCatching {
            NotificationManagerCompat.from(ctx).notify(ID_FALLBACK, buildAlarm(ctx, alarms, CH_FALLBACK))
        }
    }

    fun postReminder(ctx: Context, t: AlarmTrigger) {
        val a = RingingAlarm.from(t)
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(a.title)
            .setContentText(buildString {
                append(timeText(a)); a.location?.let { append(" · ").append(it) }
            })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(a.color)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(a.key.hashCode(), n) }
    }

    fun postMissed(ctx: Context, alarms: List<RingingAlarm>) {
        if (alarms.isEmpty()) return
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Missed alarm: ${alarms.first().title}")
            .setContentText(timeText(alarms.first()))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_MISSED, n) }
    }

    fun watcher(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_WATCHER)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Watching your calendars")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun postHealth(ctx: Context, problems: List<String>) {
        val n = NotificationCompat.Builder(ctx, CH_HEALTH)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarms may not wake you")
            .setContentText(problems.joinToString(", "))
            .setStyle(NotificationCompat.BigTextStyle().bigText("Fix in Glass Alarm → Setup:\n• " + problems.joinToString("\n• ")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_HEALTH, n) }
    }

    fun postDriving(ctx: Context, alarms: List<RingingAlarm>, held: Boolean) {
        if (alarms.isEmpty()) return
        val first = alarms.first()
        val more = if (alarms.size > 1) " +${alarms.size - 1} more" else ""
        val n = NotificationCompat.Builder(ctx, CH_DRIVING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle((if (held) "Alarm held while driving: " else "Alarm silenced while driving: ") + first.title + more)
            .setContentText(timeText(first) + if (held) " · will ring when Android Auto disconnects" else "")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_DRIVING, n) }
    }

    fun cancelDriving(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_DRIVING)

    fun cancelHealth(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_HEALTH)
}
