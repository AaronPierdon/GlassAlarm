package com.aaron.glassalarm.ui

import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaron.glassalarm.alarm.DrivingGate
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.alarm.WatcherService
import com.aaron.glassalarm.data.CarBehavior
import com.aaron.glassalarm.data.DismissStyle
import com.aaron.glassalarm.data.PerfMode
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.TriggerMode
import com.aaron.glassalarm.data.Weather

@Composable
fun SettingsScreen(activity: MainActivity, refreshKey: Int, onThemeChanged: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    var v by remember { mutableIntStateOf(0) }

    fun bump(resched: Boolean = false) {
        v++
        if (resched) Scheduler.rescheduleAsync(ctx, "settings")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      // Prefs aren't observable, so rebuild the controls whenever a setting (v) or system state (refreshKey) changes.
      key(v, refreshKey) {
        Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        // ================= LOOK =================
        SectionTitle("Alarm screen style")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(listOf(Themes.auto) + Themes.all, key = { it.id }) { t ->
                        val sel = p.themeId == t.id
                        Column(
                            Modifier.width(72.dp).clickable { p.themeId = t.id; bump(); onThemeChanged() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(t.gradient))
                                    .border(if (sel) 3.dp else 1.dp, Color.White.copy(alpha = if (sel) 1f else 0.4f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (t.id == "auto") Text("A", color = Color.White, fontWeight = FontWeight.Bold) }
                            Text(t.name, color = if (sel) Color.White else TextDim, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
                Gap()
                Row {
                    GlassButton("Preview alarm screen", primary = true) { activity.previewAlarm() }
                }
                Gap(4)
                SwitchRow("Weather on alarm screen", "Open-Meteo, no account needed", p.showWeather) { p.showWeather = it; bump() }
                SwitchRow("Local info", "City, sunrise, rain chance", p.showLocalInfo) { p.showLocalInfo = it; bump() }
                SwitchRow("Show what's next", "Your next couple of events under the alarm", p.showNextEvents) { p.showNextEvents = it; bump() }
                SwitchRow("Fahrenheit", null, p.fahrenheit) { p.fahrenheit = it; bump(); Scheduler.runAsync { Weather.refresh(ctx) } }
                SwitchRow("24-hour clock", null, p.clock24h) { p.clock24h = it; bump() }
                Gap(6)
                Text("Dismiss with", color = Color.White, fontSize = 16.sp)
                Gap(6)
                ChoiceChips(DismissStyle.entries, p.dismissStyle, { it.label }) { p.dismissStyle = it; bump() }
                Gap(10)
                var city by remember { mutableStateOf(p.weatherCity) }
                OutlinedTextField(
                    value = city, onValueChange = { city = it },
                    label = { Text("Weather city (blank = use location)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        p.weatherCity = city
                        Scheduler.runAsync { Weather.refresh(ctx) }
                        bump()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                        focusedLabelColor = Color.White, unfocusedLabelColor = TextDim, cursorColor = Color.White,
                    ),
                )
            }
        }

        // ================= TIMING =================
        SectionTitle("When to ring")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                ChoiceChips(TriggerMode.entries, p.triggerMode, { it.label }) { p.triggerMode = it; bump(true) }
                Gap(6)
                Text(
                    when (p.triggerMode) {
                        TriggerMode.START -> "Rings once per event at its start time (minus the lead time below)."
                        TriggerMode.REMINDERS -> "Rings at each reminder you set on the event in Google Calendar."
                        TriggerMode.BOTH -> "Rings at every reminder AND again at the start."
                    },
                    color = TextDim, fontSize = 13.sp,
                )
                if (p.triggerMode == TriggerMode.REMINDERS) {
                    SwitchRow(
                        "Events without reminders still ring",
                        "Unlike the other app, a reminder is NOT required — we read events directly",
                        p.fallbackWhenNoReminder,
                    ) { p.fallbackWhenNoReminder = it; bump(true) }
                }
                StepperRow(
                    "Lead time", if (p.defaultLeadMinutes == 0) "at start" else "${p.defaultLeadMinutes} min",
                    "Used for 'at start' alarms",
                    onMinus = { p.defaultLeadMinutes = (p.defaultLeadMinutes - 5).coerceAtLeast(0); bump(true) },
                    onPlus = { p.defaultLeadMinutes = p.defaultLeadMinutes + 5; bump(true) },
                )
                SwitchRow("All-day events", "Ring once on the day", p.includeAllDay) { p.includeAllDay = it; bump(true) }
                if (p.includeAllDay) {
                    val m = p.allDayMinuteOfDay
                    StepperRow(
                        "All-day alarm time", String.format("%d:%02d %s", ((m / 60) + 11) % 12 + 1, m % 60, if (m < 720) "AM" else "PM"),
                        onMinus = { p.allDayMinuteOfDay = m - 30; bump(true) },
                        onPlus = { p.allDayMinuteOfDay = m + 30; bump(true) },
                    )
                }
                SwitchRow("Skip events I declined", null, p.skipDeclined) { p.skipDeclined = it; bump(true) }
                StepperRow(
                    "Look ahead", "${p.lookaheadDays} days",
                    onMinus = { p.lookaheadDays = p.lookaheadDays - 1; bump(true) },
                    onPlus = { p.lookaheadDays = p.lookaheadDays + 1; bump(true) },
                )
            }
        }

        // ================= RINGING =================
        SectionTitle("Sound & snooze")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                val soundName = remember(v, refreshKey) {
                    val uri = p.ringtoneUri?.let { Uri.parse(it) }
                        ?: RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                    runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull() ?: "Default alarm"
                }
                Row(
                    Modifier.fillMaxWidth().clickable { activity.pickRingtone() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Alarm sound", color = Color.White, fontSize = 16.sp)
                        Text(soundName, color = TextDim, fontSize = 13.sp)
                    }
                    Text("Change", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                StepperRow("Snooze length", "${p.snoozeMinutes} min",
                    onMinus = { p.snoozeMinutes = p.snoozeMinutes - 1; bump() },
                    onPlus = { p.snoozeMinutes = p.snoozeMinutes + 1; bump() })
                StepperRow("Auto-silence after", "${p.autoSilenceMinutes} min",
                    onMinus = { p.autoSilenceMinutes = p.autoSilenceMinutes - 1; bump() },
                    onPlus = { p.autoSilenceMinutes = p.autoSilenceMinutes + 1; bump() })
                SwitchRow("Vibrate", null, p.vibrate) { p.vibrate = it; bump() }
                SwitchRow("Gentle volume ramp", "Starts quiet, reaches full volume in ~25 s", p.crescendo) { p.crescendo = it; bump() }
            }
        }

        // ================= ENGINE =================
        // ================= DRIVING =================
        SectionTitle("While driving (Android Auto)")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                ChoiceChips(CarBehavior.entries, p.carBehavior, { it.label }) {
                    p.carBehavior = it
                    bump()
                    Scheduler.runAsync { DrivingGate.checkHolds(ctx, "setting-changed") }
                }
                Gap(6)
                Text(p.carBehavior.detail, color = TextDim, fontSize = 13.sp)
                Gap(4)
                Text(
                    "Uses Android Auto's own live connection state (the official CarConnection signal), checked fresh at the moment " +
                        "each alarm fires. Only an active car-screen session counts — a leftover background process does not.",
                    color = TextFaint, fontSize = 12.sp,
                )
            }
        }

        SectionTitle("Background engine")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                ChoiceChips(PerfMode.entries, p.perfMode, { it.label }) {
                    p.perfMode = it
                    bump(true)
                    WatcherService.ensureRunning(ctx)
                    Scheduler.scheduleHeartbeat(ctx)
                }
                Gap(6)
                Text(p.perfMode.detail, color = TextDim, fontSize = 13.sp)
                Gap(4)
                Text(
                    "All modes use real alarm-clock alarms, so a scheduled alarm fires on time regardless. " +
                        "The mode only controls how fast NEW or edited events are noticed. Screen-off + nothing due soon = 4× slower checks (internal sleep).",
                    color = TextFaint, fontSize = 12.sp,
                )
            }
        }
        Gap(24)
      }
    }
}
