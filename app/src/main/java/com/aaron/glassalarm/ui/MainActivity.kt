package com.aaron.glassalarm.ui

import android.content.Intent
import android.database.ContentObserver
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.aaron.glassalarm.alarm.AlarmActivity
import com.aaron.glassalarm.alarm.PermItem
import com.aaron.glassalarm.alarm.PermissionCenter
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.alarm.WatcherService
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs

enum class Tab(val label: String, val icon: ImageVector) {
    UPCOMING("Upcoming", Icons.Default.Event),
    CALENDARS("Calendars", Icons.Default.CalendarMonth),
    STYLE("Settings", Icons.Default.Palette),
    SETUP("Setup", Icons.Default.Shield),
}

class MainActivity : ComponentActivity() {

    /** Bumped on every resume / permission result so screens re-read system state. */
    val resumeTick = mutableIntStateOf(0)

    private var wizard = false
    private val wizardTried = mutableSetOf<String>()
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var ringtoneLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            EventLog.add(this, "runtime permissions: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
            resumeTick.intValue++
            Scheduler.rescheduleAsync(this, "permission-granted")
            WatcherService.ensureRunning(this)
        }
        ringtoneLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data ?: return@registerForActivityResult
            val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            Prefs(this).ringtoneUri = uri?.toString()
            resumeTick.intValue++
        }
        val startTab = if (Prefs(this).onboardingDone) Tab.UPCOMING else Tab.SETUP
        setContent { GlassMaterialTheme { MainUi(this, startTab) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        Scheduler.rescheduleAsync(this, "app-open")
        WatcherService.ensureRunning(this)
        if (wizard) continueWizard()
    }

    // ---------------- guided "grant everything" ----------------

    fun startWizard() {
        wizard = true
        wizardTried.clear()
        continueWizard()
    }

    private fun continueWizard() {
        val items = PermissionCenter.items(this)
        val runtimeMissing = items.filter { it.granted == false && it.runtime.isNotEmpty() && it.id !in wizardTried }
        if (runtimeMissing.isNotEmpty()) {
            runtimeMissing.forEach { wizardTried += it.id }
            permLauncher.launch(runtimeMissing.flatMap { it.runtime }.distinct().toTypedArray())
            return
        }
        val next = items.firstOrNull { it.granted == false && it.action != null && it.id !in wizardTried }
            ?: items.firstOrNull { it.granted == null && it.action != null && it.id !in wizardTried && it.id == "autostart" }
        if (next != null) {
            wizardTried += next.id
            Toast.makeText(this, "Allow: ${next.title}", Toast.LENGTH_LONG).show()
            next.action?.invoke(this)
            return
        }
        wizard = false
        Prefs(this).onboardingDone = true
        resumeTick.intValue++
        Toast.makeText(this, "Setup pass complete — now run a lock-screen test", Toast.LENGTH_LONG).show()
    }

    fun fix(item: PermItem) {
        if (item.granted == false && item.runtime.isNotEmpty()) permLauncher.launch(item.runtime.toTypedArray())
        else item.action?.invoke(this) ?: PermissionCenter.openAppDetails(this)
    }

    fun requestRuntime(perms: List<String>) = permLauncher.launch(perms.toTypedArray())

    fun pickRingtone() {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
        Prefs(this).ringtoneUri?.let { i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        runCatching { ringtoneLauncher.launch(i) }
    }

    fun previewAlarm(themeId: String? = null) {
        startActivity(
            Intent(this, AlarmActivity::class.java)
                .putExtra(AlarmActivity.EXTRA_PREVIEW, true)
                .putExtra(AlarmActivity.EXTRA_THEME, themeId)
        )
    }
}

@Composable
fun MainUi(activity: MainActivity, startTab: Tab) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tab by remember { mutableStateOf(startTab) }
    val resume by activity.resumeTick
    var calendarTick by remember { mutableIntStateOf(0) }

    // Live refresh while the app is open: any calendar change re-renders lists.
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { calendarTick++ }
        }
        runCatching { ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }

    var themeVersion by remember { mutableIntStateOf(0) }
    val theme = remember(themeVersion, resume) { Themes.resolve(prefs.themeId) }
    val refreshKey = resume * 100_000 + calendarTick
    val problems = remember(resume) { PermissionCenter.criticalProblems(ctx).size }

    GlassBackground(theme, animate = true) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.UPCOMING -> UpcomingScreen(activity, refreshKey) { tab = Tab.SETUP }
                    Tab.CALENDARS -> CalendarsScreen(activity, refreshKey)
                    Tab.STYLE -> SettingsScreen(activity, refreshKey) { themeVersion++ }
                    Tab.SETUP -> SetupScreen(activity, refreshKey)
                }
            }
            // glass bottom bar
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(28.dp))
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Tab.entries.forEach { t ->
                    val sel = t == tab
                    Column(
                        Modifier.clip(RoundedCornerShape(20.dp)).clickable { tab = t }
                            .background(if (sel) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Icon(t.icon, t.label, tint = if (sel) Color.White else TextDim)
                            if (t == Tab.SETUP && problems > 0) {
                                Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(Bad))
                            }
                        }
                        Text(t.label, color = if (sel) Color.White else TextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
