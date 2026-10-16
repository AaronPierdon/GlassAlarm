package com.aaron.glassalarm.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aaron.glassalarm.data.Prefs

/**
 * One row of the Setup checklist.
 * granted: true/false when we can detect it, null when Android gives us no way to read it (OEM toggles).
 */
data class PermItem(
    val id: String,
    val title: String,
    val why: String,
    val granted: Boolean?,
    val critical: Boolean,
    val runtime: List<String> = emptyList(),
    val action: ((Activity) -> Unit)? = null,
)

object PermissionCenter {

    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun items(ctx: Context): List<PermItem> {
        val out = mutableListOf<PermItem>()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val actm = ctx.getSystemService(ActivityManager::class.java)

        out += PermItem(
            "calendar", "Read calendars", "Needed to see your events.",
            granted(ctx, Manifest.permission.READ_CALENDAR), critical = true,
            runtime = listOf(Manifest.permission.READ_CALENDAR),
        )

        out += PermItem(
            "notifications", "Notifications", "The alarm is delivered as a full-screen notification.",
            NotificationManagerCompat.from(ctx).areNotificationsEnabled(), critical = true,
            runtime = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
            action = { a -> open(a, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)) },
        )

        out += PermItem(
            "exact", "Alarms & reminders", "Lets us set real alarm-clock alarms that fire on the second, even in Doze.",
            if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true, critical = true,
            action = { a -> if (Build.VERSION.SDK_INT >= 31) open(a, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(a))) },
        )

        out += PermItem(
            "fsi", "Full-screen notifications", "Android 14+ blocks lock-screen takeovers unless this is on.",
            if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true, critical = true,
            action = { a -> if (Build.VERSION.SDK_INT >= 34) open(a, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri(a))) },
        )

        out += PermItem(
            "overlay", "Display over other apps", "Lets us launch the alarm screen directly from the background (the key OnePlus fix).",
            Settings.canDrawOverlays(ctx), critical = true,
            action = { a -> open(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(a))) },
        )

        out += PermItem(
            "turnscreenon", "Turn screen on", "Settings › Apps › Special app access › Turn screen on. Lets our wake lock light the display.",
            turnScreenOnAllowed(ctx), critical = true,
            action = { a -> openTurnScreenOn(a) },
        )

        out += PermItem(
            "battery", "Ignore battery optimization", "Stops Doze/OxygenOS from deferring us. OnePlus sometimes flips this back — we'll warn you.",
            pm.isIgnoringBatteryOptimizations(ctx.packageName), critical = true,
            action = { a ->
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(a))
                if (!open(a, i)) open(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
        )

        out += PermItem(
            "bgrestrict", "Background activity allowed", "App info › Battery usage › 'Allow background activity'.",
            !actm.isBackgroundRestricted, critical = true,
            action = { a -> openAppDetails(a) },
        )

        out += PermItem(
            "channel", "Alarm channel: urgent + lock screen", "The alarm notification channel must stay at max importance and show on the lock screen.",
            alarmChannelOk(ctx), critical = true,
            action = { a ->
                open(a, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CH_ALARM))
            },
        )

        out += PermItem(
            "dnd", "Do Not Disturb access", "Lets the alarm break through DND / Zen mode.",
            nm.isNotificationPolicyAccessGranted, critical = false,
            action = { a -> open(a, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        )

        out += PermItem(
            "location", "Approximate location", "Optional — for weather and your city name on the alarm screen.",
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION), critical = false,
            runtime = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        )

        out += PermItem(
            "autostart", "OnePlus auto-launch", "Allow auto-launch / startup so we come back after reboot or a kill. Can't be checked automatically.",
            null, critical = false,
            action = { a -> OemHelper.openAutoStart(a) },
        )

        out += PermItem(
            "unused", "Don't pause if unused", "App info › turn OFF 'Pause app activity if unused' / 'Manage unused apps'.",
            null, critical = false,
            action = { a ->
                val ok = Build.VERSION.SDK_INT >= 30 && open(a, Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkgUri(a)))
                if (!ok) openAppDetails(a)
            },
        )
        return out
    }

    fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun alarmChannelOk(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(Notifications.CH_ALARM) ?: return false
        return ch.importance >= NotificationManager.IMPORTANCE_HIGH &&
            ch.lockscreenVisibility != android.app.Notification.VISIBILITY_SECRET
    }

    /** Uses the hidden-but-stable app-op string; returns null if this Android build doesn't have it. */
    fun turnScreenOnAllowed(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            when (ops.unsafeCheckOpNoThrow("android:turn_screen_on", Process.myUid(), ctx.packageName)) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
                else -> granted(ctx, "android.permission.TURN_SCREEN_ON")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openTurnScreenOn(a: Activity) {
        val candidates = listOf(
            Intent("android.settings.MANAGE_APP_TURN_SCREEN_ON", pkgUri(a)),
            Intent("android.settings.TURN_SCREEN_ON_SETTINGS"),
            Intent("android.settings.MANAGE_SPECIAL_APP_ACCESS_SETTINGS"),
        )
        if (candidates.none { open(a, it) }) openAppDetails(a)
    }

    fun openAppDetails(a: Activity) {
        open(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(a)))
    }

    /** Returns true if something handled the intent. */
    fun open(a: Activity, i: Intent): Boolean = try {
        a.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }

    /** The subset that, when missing, means alarms probably won't wake the phone. */
    fun criticalProblems(ctx: Context): List<String> =
        items(ctx).filter { it.critical && it.granted == false }.map { it.title }
}

object OemHelper {
    val isOnePlusFamily: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("oneplus") || it.contains("oppo") || it.contains("realme") }

    private val autoStart = listOf(
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
    )

    fun openAutoStart(a: Activity) {
        for (cn in autoStart) {
            if (PermissionCenter.open(a, Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        }
        PermissionCenter.openAppDetails(a)
    }
}

object HealthCheck {
    /** Re-checks critical permissions; posts a warning notification when something got revoked (OnePlus does this). */
    fun run(ctx: Context, quiet: Boolean) {
        val prefs = Prefs(ctx)
        val problems = PermissionCenter.criticalProblems(ctx)
        val sig = problems.sorted().joinToString("|")
        val lastSig = prefs.sp.getString("healthSig", "") ?: ""
        val lastAt = prefs.sp.getLong("healthAt", 0L)
        val now = System.currentTimeMillis()
        if (problems.isEmpty()) {
            if (lastSig.isNotEmpty()) Notifications.cancelHealth(ctx)
            prefs.sp.edit().putString("healthSig", "").apply()
            return
        }
        if (!prefs.onboardingDone) return // don't nag during first setup
        if (sig != lastSig || now - lastAt > 6 * 3600_000L || !quiet) {
            Notifications.postHealth(ctx, problems)
            prefs.sp.edit().putString("healthSig", sig).putLong("healthAt", now).apply()
        }
    }
}
