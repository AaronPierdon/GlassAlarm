package com.aaron.glassalarm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaron.glassalarm.alarm.PermissionCenter
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.alarm.UpcomingItem
import com.aaron.glassalarm.data.AlertMode
import com.aaron.glassalarm.data.CalendarRepo
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.Weather
import com.aaron.glassalarm.data.WeatherNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun UpcomingScreen(activity: MainActivity, refreshKey: Int, goToSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var localTick by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val events by produceState(initialValue = emptyList<UpcomingItem>(), refreshKey, localTick) {
        value = withContext(Dispatchers.IO) { runCatching { Scheduler.upcoming(ctx) }.getOrDefault(emptyList()) }
    }
    val weather by produceState<WeatherNow?>(initialValue = Weather.cached(ctx), refreshKey) {
        if (prefs.showWeather) value = withContext(Dispatchers.IO) { runCatching { Weather.refreshIfStale(ctx, 60) }.getOrNull() } ?: value
    }
    val problems = remember(refreshKey) { PermissionCenter.criticalProblems(ctx) }
    var syncing by remember { mutableStateOf(false) }
    var seriesTarget by remember { mutableStateOf<UpcomingItem?>(null) }

    val today = LocalDate.now()
    val grouped = events.groupBy { dayOf(it) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------- header ----------
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(today.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())), color = TextDim, fontSize = 14.sp)
                }
                val w = weather
                if (w != null && prefs.showWeather) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${w.emoji} ${w.temp}°${w.unit}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOf(w.place, w.description).filter { it.isNotBlank() }.joinToString(" · "), color = TextDim, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).glass(CircleShape).clickable {
                        syncing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                CalendarRepo.requestSync(ctx, prefs)
                                Scheduler.reschedule(ctx, "manual-refresh")
                            }
                            delay(1500)
                            localTick++
                            syncing = false
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Refresh, "sync", tint = if (syncing) TextFaint else Color.White) }
            }
        }

        if (problems.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().clickable { goToSetup() }, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null, tint = Warn)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Alarms may not wake you", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Missing: ${problems.joinToString(", ")}. Tap to fix.", color = TextDim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ---------- next alarm hero ----------
        item {
            val next = events.flatMap { it.triggers }.filter { it.triggerAt >= now - 60_000 }.minByOrNull { it.triggerAt }
            GlassCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                Column {
                    Text("NEXT ALARM", color = TextDim, fontSize = 12.sp, letterSpacing = 1.5.sp)
                    if (next == null) {
                        Text("Nothing scheduled", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (!CalendarRepo.hasPermission(ctx)) "Grant calendar access in Setup" else "Add an event and it'll appear here within seconds",
                            color = TextDim, fontSize = 13.sp,
                        )
                    } else {
                        val mins = ((next.triggerAt - now) / 60_000L).toInt().coerceAtLeast(0)
                        Text("in ${humanMinutes(mins)}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${fmtTime(next.triggerAt, prefs.clock24h)} · ${next.instance.title}",
                            color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (events.isEmpty()) {
            item {
                Text("No events in the next ${prefs.lookaheadDays} days in your selected calendars.", color = TextDim, modifier = Modifier.padding(8.dp))
            }
        }

        grouped.forEach { (day, dayItems) ->
            item(key = "h$day") {
                val label = when (day) {
                    today -> "Today"
                    today.plusDays(1) -> "Tomorrow"
                    else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
                }
                SectionTitle(label)
            }
            items(dayItems, key = { it.instance.instanceKey }) { item ->
                EventRow(
                    item, now, prefs.clock24h,
                    seriesMode = if (item.instance.recurring) prefs.seriesMode(item.instance.eventId) else null,
                    onToggle = {
                        prefs.setInstanceMode(item.instance.instanceKey, item.mode.next())
                        Scheduler.rescheduleAsync(ctx, "toggle")
                        localTick++
                    },
                    onLongPress = { if (item.instance.recurring) seriesTarget = item },
                )
            }
        }
    }

    // Whole-series options for a recurring event (long-press its bell).
    seriesTarget?.let { target ->
        val setSeries = { mode: AlertMode ->
            prefs.setSeriesMode(target.instance.eventId, mode)
            Scheduler.rescheduleAsync(ctx, "series")
            localTick++
            seriesTarget = null
        }
        AlertDialog(
            onDismissRequest = { seriesTarget = null },
            containerColor = Color(0xFF1B2236),
            title = { Text("Every \"${target.instance.title}\"", color = Color.White) },
            text = { Text("Applies to all occurrences of this repeating event, including future ones. Tap the bell on a single day to override just that day.", color = TextDim) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { setSeries(AlertMode.ALARM) }) { Text("Alarm for whole series", color = Color.White) }
                    TextButton(onClick = { setSeries(AlertMode.NOTIFY) }) { Text("Notify only for whole series", color = Color.White) }
                    TextButton(onClick = { setSeries(AlertMode.OFF) }) { Text("Turn off whole series", color = Bad) }
                    TextButton(onClick = { seriesTarget = null }) { Text("Cancel", color = TextDim) }
                }
            },
        )
    }
}

private fun dayOf(item: UpcomingItem): LocalDate {
    val ms = if (item.instance.allDay) CalendarRepo.allDayLocalStart(item.instance.begin) else item.instance.begin
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}

@Composable
private fun EventRow(
    item: UpcomingItem,
    now: Long,
    h24: Boolean,
    seriesMode: AlertMode?,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val inst = item.instance
    val ongoing = !inst.allDay && inst.begin <= now && inst.end > now
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // time column
            Column(Modifier.width(76.dp)) {
                Text(if (inst.allDay) "All day" else fmtTime(inst.begin, h24), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val sub = when {
                    inst.allDay -> ""
                    ongoing -> "now"
                    else -> "in ${humanMinutes(((inst.begin - now) / 60_000L).toInt().coerceAtLeast(0))}"
                }
                if (sub.isNotEmpty()) Text(sub, color = TextDim, fontSize = 12.sp)
            }
            Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Color(inst.color)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(inst.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val repeatTag = when {
                    !inst.recurring -> ""
                    seriesMode == AlertMode.OFF -> "↻ Series off · "
                    seriesMode == AlertMode.NOTIFY -> "↻ Series: notify · "
                    else -> "↻ "
                }
                Text(repeatTag + triggerSummary(item, h24), color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inst.location?.let { Text(it, color = TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(8.dp))
            ModeButton(item.mode, onToggle, onLongPress)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ModeButton(mode: AlertMode, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (icon, bg, tint) = when (mode) {
        AlertMode.ALARM -> Triple(Icons.Default.Alarm, Color.White, Color(0xFF0B1026))
        AlertMode.NOTIFY -> Triple(Icons.Default.NotificationsActive, Color(0xFF3B82F6), Color.White)
        AlertMode.OFF -> Triple(Icons.Default.NotificationsOff, Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.7f))
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, mode.name, tint = tint, modifier = Modifier.size(22.dp)) }
}

private fun triggerSummary(item: UpcomingItem, h24: Boolean): String {
    val inst = item.instance
    return when {
        item.mode == AlertMode.OFF -> if (inst.recurring) "Off" else "Alerts off for this event"
        item.triggers.isEmpty() && inst.allDay -> "All-day alarms are off (Settings)"
        item.triggers.isEmpty() -> "No alarm pending"
        else -> {
            val verb = if (item.mode == AlertMode.NOTIFY) "Notify" else "Alarm"
            item.triggers.joinToString(" + ") { t ->
                val whenTxt = when {
                    t.snoozed -> "snoozed → ${fmtTime(t.triggerAt, h24)}"
                    inst.allDay -> fmtTime(t.triggerAt, h24)
                    t.minutesBefore == 0 -> "at start"
                    else -> "${humanMinutes(t.minutesBefore)} before"
                }
                whenTxt
            }.let { "$verb $it" }
        }
    }
}

@Suppress("unused")
private fun localTime(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
