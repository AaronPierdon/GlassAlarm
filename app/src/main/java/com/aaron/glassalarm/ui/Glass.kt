package com.aaron.glassalarm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/** A colour story for the alarm screen + app background. */
data class GlassTheme(
    val id: String,
    val name: String,
    val gradient: List<Color>,
    val blobs: List<Color>,
    val accent: Color,
    val text: Color = Color.White,
)

object Themes {
    val all = listOf(
        GlassTheme("sky", "Morning Sky", listOf(Color(0xFF3F8FD6), Color(0xFF8CC5EE), Color(0xFFF3E3C3)), listOf(Color(0xFFFFE7A3), Color(0xFFFFFFFF)), Color(0xFFFFC857)),
        GlassTheme("aurora", "Aurora", listOf(Color(0xFF0B1026), Color(0xFF1B2E5A), Color(0xFF2B7A78)), listOf(Color(0xFF3DFFB0), Color(0xFF8A5CFF)), Color(0xFF3DFFB0)),
        GlassTheme("sunset", "Sunset", listOf(Color(0xFF2E1A47), Color(0xFFC0426B), Color(0xFFF7A26B)), listOf(Color(0xFFFFD27F), Color(0xFFFF6F91)), Color(0xFFFFD27F)),
        GlassTheme("midnight", "Midnight Glass", listOf(Color(0xFF050608), Color(0xFF111827), Color(0xFF1F2937)), listOf(Color(0xFF6366F1), Color(0xFF0EA5E9)), Color(0xFF818CF8)),
        GlassTheme("ocean", "Ocean", listOf(Color(0xFF023047), Color(0xFF219EBC), Color(0xFF8ECAE6)), listOf(Color(0xFFFFFFFF), Color(0xFF48CAE4)), Color(0xFFFFB703)),
        GlassTheme("rose", "Rose Quartz", listOf(Color(0xFF3B0A2A), Color(0xFF9D4EDD), Color(0xFFF7B2D9)), listOf(Color(0xFFFFC6FF), Color(0xFFFF8FAB)), Color(0xFFFFC6FF)),
        GlassTheme("forest", "Forest", listOf(Color(0xFF0B3D2E), Color(0xFF2D6A4F), Color(0xFF95D5B2)), listOf(Color(0xFFD8F3DC), Color(0xFFB7E4C7)), Color(0xFFD8F3DC)),
        GlassTheme("mono", "Graphite", listOf(Color(0xFF111111), Color(0xFF2A2A2A), Color(0xFF555555)), listOf(Color(0xFFFFFFFF), Color(0xFF9CA3AF)), Color(0xFFFFFFFF)),
    )

    val auto = GlassTheme("auto", "Auto (time of day)", listOf(Color(0xFF3F8FD6), Color(0xFFC0426B), Color(0xFF111827)), listOf(Color.White, Color.White), Color.White)

    fun resolve(id: String, now: LocalTime = LocalTime.now()): GlassTheme {
        if (id != "auto") return all.firstOrNull { it.id == id } ?: all[0]
        val h = now.hour
        return when (h) {
            in 5..10 -> all.first { it.id == "sky" }
            in 11..16 -> all.first { it.id == "ocean" }
            in 17..19 -> all.first { it.id == "sunset" }
            else -> all.first { it.id == "aurora" }
        }
    }
}

@Composable
fun GlassMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color(0xFF0B1026),
            secondary = Color(0xFFB4C6FF),
            surface = Color(0x33FFFFFF),
            onSurface = Color.White,
            background = Color.Transparent,
            onBackground = Color.White,
        ),
        content = content,
    )
}

/** Frosted glass: translucent white gradient, glossy top edge, hairline light border. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.26f * strength), Color.White.copy(alpha = 0.10f * strength))
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.70f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.35f)),
            start = Offset.Zero, end = Offset.Infinite,
        ),
        shape,
    )

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    padding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape)) {
        // glossy highlight across the top third
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.18f),
                        0.35f to Color.Transparent,
                    )
                )
        )
        Box(Modifier.padding(padding), content = content)
    }
}

/** Animated gradient + soft glowing blobs, the backdrop behind every glass surface. */
@Composable
fun GlassBackground(theme: GlassTheme, modifier: Modifier = Modifier, animate: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (animate) 14000 else 1_000_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phase",
    )
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(theme.gradient))) {
        Canvas(Modifier.fillMaxSize().blur(60.dp)) {
            val w = size.width
            val h = size.height
            val b1 = Offset(w * (0.15f + 0.25f * phase), h * (0.22f + 0.06f * phase))
            val b2 = Offset(w * (0.90f - 0.30f * phase), h * (0.62f - 0.10f * phase))
            drawCircle(
                Brush.radialGradient(listOf(theme.blobs[0].copy(alpha = 0.75f), Color.Transparent), center = b1, radius = w * 0.55f),
                radius = w * 0.55f, center = b1,
            )
            drawCircle(
                Brush.radialGradient(listOf(theme.blobs[1].copy(alpha = 0.45f), Color.Transparent), center = b2, radius = w * 0.65f),
                radius = w * 0.65f, center = b2,
            )
        }
        content()
    }
}
