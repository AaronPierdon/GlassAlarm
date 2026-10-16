package com.aaron.glassalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaron.glassalarm.alarm.AndroidAuto
import com.aaron.glassalarm.alarm.DrivingGate
import com.aaron.glassalarm.alarm.OemHelper
import com.aaron.glassalarm.alarm.PermissionCenter
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.alarm.WatcherService
import com.aaron.glassalarm.data.EventLog
import com.aaron.glassalarm.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SetupScreen(activity: MainActivity, refreshKey: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var logTick by remember { mutableIntStateOf(0) }
    val perms = remember(refreshKey) { PermissionCenter.items(ctx) }
    val critical = perms.filter { it.critical && it.granted == false }
    val log = remember(refreshKey, logTick) { EventLog.lines(ctx).take(60) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Setup", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (critical.isEmpty()) "Everything the lock-screen alarm needs is granted ✓"
                    else "${critical.size} thing${if (critical.size == 1) "" else "s"} still blocking lock-screen alarms",
                    color = if (critical.isEmpty()) Good else Warn, fontSize = 14.sp,
                )
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("One tap: walk through every permission", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Each settings page opens in turn — flip the switch, press back, and the next one opens.", color = TextDim, fontSize = 13.sp)
                    Gap(10)
                    GlassButton("Grant everything", Modifier.fillMaxWidth(), primary = true) { activity.startWizard() }
                }
            }
        }

        item { SectionTitle("Permissions") }
        items(perms, key = { it.id }) { item ->
            GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(item.granted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title + if (item.critical) "" else "  (optional)",
                            color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        )
                        Text(item.why, color = TextDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (item.granted != true && (item.action != null || item.runtime.isNotEmpty())) {
                        GlassButton(if (item.granted == null) "Open" else "Fix") { activity.fix(item) }
                    } else if (item.granted == true) {
                        Text("OK", color = Good, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item { SectionTitle("Prove it works") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Tap a test, then LOCK THE PHONE and put it down. The real alarm path runs: alarm-clock alarm → service → full-screen + wake.",
                        color = TextDim, fontSize = 13.sp,
                    )
                    Gap(10)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Test in 15 s", Modifier.weight(1f), primary = true) { Scheduler.scheduleTest(ctx, 15); logTick++ }
                        GlassButton("Test in 60 s", Modifier.weight(1f)) { Scheduler.scheduleTest(ctx, 60); logTick++ }
                    }
                    Gap(8)
                    GlassButton("Preview screen (no sound)", Modifier.fillMaxWidth()) { activity.previewAlarm() }
                }
            }
        }

        run {
            item { SectionTitle("OnePlus 12 checklist (can't be detected — do once)") }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Recents: open the app switcher, tap this app's icon/menu → Lock. This is the #1 thing that stops OxygenOS killing it.",
                            "App info → Battery usage → Allow background activity (you already did this for the other app).",
                            "App info → Battery usage → turn on 'Allow auto launch' if your build shows it (or the 'OnePlus auto-launch' row above).",
                            "Settings → Notifications → Lock screen → 'Show all notifications' (alarms need lock-screen visibility).",
                            "Settings → Apps → Special app access → Turn screen on → Glass Alarm: Allowed.",
                            "App info → Notifications → 'Alarms (lock screen)' → Allow, Banners + Lock screen ON, Importance Urgent.",
                            "If an alarm still only shows as a banner: Settings → Battery → turn off 'Sleep standby optimization' / 'Smart battery'.",
                            "Optional nuclear option: Developer options → disable 'Suspend execution for cached apps'.",
                        ).forEachIndexed { i, s ->
                            Row {
                                Text("${i + 1}.", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                                Text(s, color = TextDim, fontSize = 13.sp)
                            }
                        }
                        Gap(4)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton("App info", Modifier.weight(1f)) { PermissionCenter.openAppDetails(activity) }
                            GlassButton("Auto-launch", Modifier.weight(1f)) { OemHelper.openAutoStart(activity) }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Android Auto detection") }
        item {
            var carState by remember { mutableIntStateOf(Int.MIN_VALUE) }
            var holds by remember { mutableIntStateOf(0) }
            LaunchedEffect(refreshKey, logTick) {
                while (true) {
                    carState = withContext(Dispatchers.IO) { AndroidAuto.state(ctx) }
                    holds = prefs.holds().size
                    delay(3_000)
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            when (carState) {
                                AndroidAuto.PROJECTION -> false
                                AndroidAuto.NOT_CONNECTED -> true
                                else -> null
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (carState == Int.MIN_VALUE) "Checking…" else AndroidAuto.label(carState),
                            color = Color.White, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "Live, refreshes every 3 s. Verify once in the car: it should say 'projecting' only while Android Auto " +
                            "is on the car screen, and flip back within seconds of unplugging/turning the car off.",
                        color = TextDim, fontSize = 12.sp,
                    )
                    Text("While driving: ${prefs.carBehavior.label}" + if (holds > 0) " · $holds alarm(s) held" else "", color = TextDim, fontSize = 12.sp)
                    if (holds > 0) {
                        Gap(8)
                        GlassButton("Release held alarms now", Modifier.fillMaxWidth()) {
                            Scheduler.runAsync { DrivingGate.checkHolds(ctx, "manual") }
                            logTick++
                        }
                    }
                }
            }
        }

        item { SectionTitle("Diagnostics") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    Text(
                        "Next alarm: " + if (prefs.nextAlarmAt > 0) "${df.format(Date(prefs.nextAlarmAt))} — ${prefs.nextAlarmTitle}" else "none",
                        color = Color.White, fontSize = 13.sp,
                    )
                    Text("Watcher service: " + if (WatcherService.running) "running (${prefs.perfMode.label})" else "not running", color = Color.White, fontSize = 13.sp)
                    Text("Last rescan: " + if (prefs.lastRescanAt > 0) df.format(Date(prefs.lastRescanAt)) else "never", color = Color.White, fontSize = 13.sp)
                    Text("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}", color = TextDim, fontSize = 12.sp)
                    Gap(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Rescan now", Modifier.weight(1f)) { Scheduler.runAsync { Scheduler.reschedule(ctx, "manual") }; logTick++ }
                        GlassButton("Clear log", Modifier.weight(1f)) { EventLog.clear(ctx); logTick++ }
                    }
                    Gap(8)
                    log.forEach { line ->
                        Text(line, color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}
