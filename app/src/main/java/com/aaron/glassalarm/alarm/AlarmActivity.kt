package com.aaron.glassalarm.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.RingingAlarm
import com.aaron.glassalarm.ui.AlarmScreen
import com.aaron.glassalarm.ui.GlassMaterialTheme

/**
 * The glass lock-screen alarm. Shows over the keyguard WITHOUT unlocking the phone.
 * Lock-screen flags are applied every way Android has ever supported (manifest attrs, API 27 methods, legacy window flags)
 * because OxygenOS has historically honoured different ones in different releases.
 */
class AlarmActivity : ComponentActivity() {

    companion object {
        @Volatile var isVisible = false
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_THEME = "theme"
    }

    private val previewMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLockScreenFlags()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        previewMode.value = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        if (!previewMode.value) EventLog.add(this, "alarm screen shown")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewMode.value) finish() // a real alarm can't be backed out of — dismiss or snooze it
            }
        })

        setContent {
            GlassMaterialTheme {
                val preview by previewMode
                val ringing by AlarmRingService.ringing.collectAsState()
                val persisted = remember(ringing) { RingingAlarm.listFromJson(Prefs(this).ringingJson) }
                val alarms = when {
                    preview -> listOf(sample())
                    ringing.isNotEmpty() -> ringing
                    else -> persisted
                }
                LaunchedEffect(alarms.isEmpty(), preview) {
                    if (!preview && alarms.isEmpty()) finish()
                }
                if (alarms.isNotEmpty()) {
                    AlarmScreen(
                        alarms = alarms,
                        themeOverride = intent.getStringExtra(EXTRA_THEME),
                        preview = preview,
                        onDismiss = {
                            if (!preview) AlarmRingService.dismiss(this)
                            finish()
                        },
                        onSnooze = { min ->
                            if (!preview) AlarmRingService.snooze(this, min)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenFlags()
        previewMode.value = intent.getBooleanExtra(EXTRA_PREVIEW, false)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun sample(): RingingAlarm {
        val now = System.currentTimeMillis()
        return RingingAlarm(
            key = "preview", eventId = -1, title = "Design handoff",
            begin = now + 15 * 60_000L, end = now + 45 * 60_000L, allDay = false,
            location = "Studio 4B", calendarName = "Work", color = 0xFF34D399.toInt(), minutesBefore = 15,
        )
    }
}
