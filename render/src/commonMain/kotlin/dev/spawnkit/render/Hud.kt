package dev.spawnkit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spawnkit.engine.RuntimeWorld
import kotlin.math.roundToLong

/** Heads-up display: one chip per variable listed in `GameSettings.hud`, refreshed whenever [tick] changes. */
@Composable
internal fun Hud(
    world: RuntimeWorld,
    tick: Long,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    val labels =
        remember(world, tick) {
            val names = world.project.variables.associate { it.id to it.name }
            world.project.settings.hud
                .map { id -> "${names[id] ?: id.value}  ${formatValue(world.variable(id))}" }
        }
    if (labels.isEmpty()) return
    Row(modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            Text(
                text = label,
                style = TextStyle(fontFamily = fontFamily, fontSize = 18.sp, color = Color.White),
                modifier =
                    Modifier
                        .background(Color(0xAA0B0D12), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Whole numbers without decimals, otherwise one decimal place. */
internal fun formatValue(value: Double): String {
    val rounded = value.roundToLong()
    return if (value == rounded.toDouble()) rounded.toString() else ((value * 10).roundToLong() / 10.0).toString()
}
