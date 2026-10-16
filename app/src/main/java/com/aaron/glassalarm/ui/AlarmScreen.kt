package com.aaron.glassalarm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.data.CalendarRepo
import com.aaron.glassalarm.data.DismissStyle
import com.aaron.glassalarm.data.Prefs
import com.aaron.glassalarm.data.RingingAlarm
import com.aaron.glassalarm.data.Weather
import com.aaron.glassalarm.data.WeatherNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AlarmScreen(
    alarms: List<RingingAlarm>,
    themeOverride: String?,
    preview: Boolean,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val theme = remember(themeOverride) { Themes.resolve(themeOverride ?: prefs.themeId) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val weather by produceState<WeatherNow?>(initialValue = Weather.cached(ctx)) {
        if (prefs.showWeather) value = withContext(Dispatchers.IO) { runCatching { Weather.refreshIfStale(ctx, 30) }.getOrNull() } ?: value
    }
    val nextUp by produceState(initialValue = emptyList<String>()) {
        if (prefs.showNextEvents) value = withContext(Dispatchers.IO) {
            runCatching {
                val keys = alarms.map { it.key.substringBefore('#') }.toSet()
                Scheduler.upcoming(ctx).filter { it.instance.instanceKey !in keys && !it.instance.allDay }.take(2).map {
                    "${fmtTime(it.instance.begin, prefs.clock24h)}  ${it.instance.title}"
                }
            }.getOrDefault(emptyList())
        }
    }

    var snoozeMin by remember { mutableIntStateOf(prefs.snoozeMinutes) }
    val first = alarms.first()
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())

    // gentle "breathing" pulse on the clock
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 1.025f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p",
    )

    GlassBackground(theme, dim = 0.20f) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // --- top bar: date + weather pill ---
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(local.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())), color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(local.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())), color = theme.text.copy(alpha = 0.75f), fontSize = 13.sp)
                }
                val w = weather
                if (prefs.showWeather && w != null) {
                    GlassCard(shape = RoundedCornerShape(50), padding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.emoji, fontSize = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("${w.temp}°", color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (prefs.showLocalInfo && w.place.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(w.place, color = theme.text.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(0.6f))

            // --- greeting + big clock ---
            Text(greeting(local.hour), color = theme.text.copy(alpha = 0.9f), fontSize = 26.sp, fontWeight = FontWeight.Light)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.scale(pulse)) {
                val pattern = if (prefs.clock24h) "HH:mm" else "h:mm"
                Text(local.format(DateTimeFormatter.ofPattern(pattern)), color = theme.text, fontSize = 92.sp, fontWeight = FontWeight.Thin, letterSpacing = (-2).sp)
                if (!prefs.clock24h) {
                    Text(local.format(DateTimeFormatter.ofPattern("a", Locale.US)), color = theme.text.copy(alpha = 0.8f), fontSize = 22.sp, modifier = Modifier.padding(bottom = 20.dp, start = 6.dp))
                }
            }

            Spacer(Modifier.height(18.dp))

            // --- event card ---
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    alarms.take(3).forEachIndexed { idx, a ->
                        if (idx > 0) Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(5.dp).height(if (idx == 0) 54.dp else 36.dp).clip(RoundedCornerShape(3.dp)).background(Color(a.color)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    a.title, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontSize = if (idx == 0) 22.sp else 16.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${timeRange(a, prefs.clock24h)} · ${relative(a, now)}",
                                    color = theme.text.copy(alpha = 0.8f), fontSize = 14.sp,
                                )
                            }
                        }
                        if (idx == 0) {
                            a.location?.let { loc ->
                                Row(Modifier.padding(start = 17.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Place, null, tint = theme.text.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(loc, color = theme.text.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (alarms.size > 3) Text("+${alarms.size - 3} more", color = theme.text.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                    if (nextUp.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
                        Spacer(Modifier.height(8.dp))
                        Text("LATER", color = theme.text.copy(alpha = 0.6f), fontSize = 11.sp, letterSpacing = 1.5.sp)
                        nextUp.forEach { Text(it, color = theme.text.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // --- snooze pill:  (−)  SNOOZE 5 min  (+) ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundGlassButton(onClick = { snoozeMin = (snoozeMin - 5).coerceAtLeast(1).let { if (snoozeMin <= 5) maxOf(1, snoozeMin - 1) else it } }) {
                    Icon(Icons.Default.Remove, "less", tint = theme.text)
                }
                Spacer(Modifier.width(12.dp))
                GlassCard(
                    Modifier.clickable { onSnooze(snoozeMin) },
                    shape = RoundedCornerShape(50), padding = 14.dp,
                ) {
                    Text("SNOOZE  $snoozeMin min", color = theme.text, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 18.dp))
                }
                Spacer(Modifier.width(12.dp))
                RoundGlassButton(onClick = { snoozeMin = if (snoozeMin < 5) snoozeMin + 1 else (snoozeMin + 5).coerceAtMost(120) }) {
                    Icon(Icons.Default.Add, "more", tint = theme.text)
                }
            }

            Spacer(Modifier.height(18.dp))

            if (prefs.dismissStyle == DismissStyle.SLIDE) {
                SlideToDismiss("Slide to dismiss", theme.accent, onDismiss)
            } else {
                Box(
                    Modifier.fillMaxWidth().height(64.dp).glass(RoundedCornerShape(50)).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("DISMISS", color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }

            // --- weather strip ---
            val w = weather
            if (prefs.showWeather && w != null && w.hourly.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            w.hourly.forEach { h ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(h.label, color = theme.text.copy(alpha = 0.75f), fontSize = 12.sp)
                                    Text(Weather.emoji(h.code), fontSize = 20.sp)
                                    Text("${h.temp}°${w.unit}", color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        if (prefs.showLocalInfo) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${w.description} · H ${w.high}° L ${w.low}° · ${w.precipChance}% rain" +
                                    (if (w.sunrise.isNotBlank()) " · ☀ ${w.sunrise}" else ""),
                                color = theme.text.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                }
            }
            if (preview) {
                Text("Preview — no sound", color = theme.text.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun RoundGlassButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).glass(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun SlideToDismiss(label: String, accent: Color, onDone: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(64.dp).glass(RoundedCornerShape(50)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumb = 56.dp
        val density = LocalDensity.current
        val maxPx = with(density) { (maxWidth - thumb - 8.dp).toPx() }.coerceAtLeast(1f)
        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        var done by remember { mutableStateOf(false) }

        val shimmer by rememberInfiniteTransition(label = "sh").animateFloat(0.45f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s")
        Text(
            label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center).alpha(((1f - offset.value / maxPx) * shimmer).coerceIn(0f, 1f)),
        )
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f))
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > maxPx * 0.75f && !done) {
                                    done = true
                                    offset.animateTo(maxPx)
                                    onDone()
                                } else offset.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(0f, maxPx)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "dismiss", tint = accent.copy(alpha = 1f).let { if (it == Color.White) Color(0xFF111827) else it })
        }
        // invisible right-edge hint
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp))
    }
}

private fun greeting(hour: Int) = when (hour) {
    in 4..11 -> "Good Morning"
    in 12..16 -> "Good Afternoon"
    in 17..21 -> "Good Evening"
    else -> "Good Night"
}

fun fmtTime(ms: Long, h24: Boolean): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US))

private fun timeRange(a: RingingAlarm, h24: Boolean): String =
    if (a.allDay) "All day" else "${fmtTime(a.begin, h24)} – ${fmtTime(a.end, h24)}"

private fun relative(a: RingingAlarm, now: Long): String {
    if (a.allDay) return "today"
    val start = if (a.allDay) CalendarRepo.allDayLocalStart(a.begin) else a.begin
    val diffMin = ((start - now) / 60_000L).toInt()
    return when {
        abs(diffMin) < 1 -> "starting now"
        diffMin > 0 -> "in ${humanMinutes(diffMin)}"
        else -> "started ${humanMinutes(-diffMin)} ago"
    }
}

fun humanMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    m < 24 * 60 -> if (m % 60 == 0) "${m / 60} hr" else "${m / 60} hr ${m % 60} min"
    else -> "${m / (24 * 60)} d"
}
