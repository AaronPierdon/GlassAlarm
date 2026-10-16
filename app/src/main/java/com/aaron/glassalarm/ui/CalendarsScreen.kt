package com.aaron.glassalarm.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.data.CalendarInfo
import com.aaron.glassalarm.data.CalendarRepo
import com.aaron.glassalarm.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CalendarsScreen(activity: MainActivity, refreshKey: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }

    val calendars by produceState(initialValue = emptyList<CalendarInfo>(), refreshKey) {
        value = withContext(Dispatchers.IO) { CalendarRepo.calendars(ctx) }
    }
    val disabled = remember(tick, refreshKey) { prefs.disabledCalendars() }
    val byAccount = calendars.groupBy { it.accountName }

    fun changed() {
        tick++
        Scheduler.rescheduleAsync(ctx, "calendar-selection")
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Calendars", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Pick which accounts and calendars can ring. New calendars are on by default.", color = TextDim, fontSize = 14.sp)
            }
        }
        if (!CalendarRepo.hasPermission(ctx)) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("Calendar access needed", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Gap(8)
                        GlassButton("Grant calendar access", primary = true) { activity.requestRuntime(listOf(Manifest.permission.READ_CALENDAR)) }
                    }
                }
            }
        }
        if (calendars.isNotEmpty()) {
            item {
                GlassButton("Sync accounts now") {
                    Scheduler.runAsync { CalendarRepo.requestSync(ctx, prefs); Scheduler.reschedule(ctx, "manual-sync") }
                }
            }
        }
        byAccount.forEach { (account, cals) ->
            item(key = account) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        val allOn = cals.none { it.id in disabled }
                        SwitchRow(
                            title = account,
                            subtitle = "${cals.size} calendar${if (cals.size == 1) "" else "s"} · ${friendlyType(cals.first().accountType)}",
                            checked = allOn,
                        ) { on ->
                            cals.forEach { prefs.setCalendarEnabled(it.id, on) }
                            changed()
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).size(1.dp).background(Color.White.copy(alpha = 0.15f)))
                        cals.forEach { cal ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal.color)))
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f)) {
                                    SwitchRow(
                                        title = cal.name,
                                        subtitle = if (!cal.visible) "Hidden in your calendar app" else null,
                                        checked = cal.id !in disabled,
                                    ) { on ->
                                        prefs.setCalendarEnabled(cal.id, on)
                                        changed()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun friendlyType(t: String) = when {
    t.contains("google") -> "Google"
    t.contains("exchange") || t.contains("eas") -> "Exchange"
    t.contains("outlook") || t.contains("microsoft") -> "Outlook"
    t.contains("LOCAL", ignoreCase = true) -> "On this phone"
    t.isBlank() -> "Local"
    else -> t.substringAfterLast('.')
}
