package com.aaron.glassalarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TextDim = Color.White.copy(alpha = 0.82f)
val TextFaint = Color.White.copy(alpha = 0.62f)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFFF6B6B)
val Warn = Color(0xFFFBBF24)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = TextDim, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 13.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34D399),
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
fun StepperRow(title: String, value: String, subtitle: String? = null, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 13.sp)
        }
        SmallRound(onMinus) { Icon(Icons.Default.Remove, "minus", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
        SmallRound(onPlus) { Icon(Icons.Default.Add, "plus", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun SmallRound(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(34.dp).glass(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) Color.White else Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = if (sel) 1f else 0.35f), RoundedCornerShape(50))
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(label(o), color = if (sel) Color(0xFF0B1026) else Color.White, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .then(if (primary) Modifier.background(Color.White) else Modifier.glass(RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Color(0xFF0B1026) else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun StatusDot(ok: Boolean?) {
    val c = when (ok) { true -> Good; false -> Bad; null -> Warn }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.size(h.dp))

@Composable
fun HGap(w: Int = 12) = Spacer(Modifier.width(w.dp))
