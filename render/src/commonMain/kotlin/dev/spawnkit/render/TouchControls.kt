package dev.spawnkit.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.spawnkit.engine.InputAction

/** On-screen buttons for touch devices: left/right on the left side, jump on the right. Each button tracks its own pointer. */
@Composable
internal fun TouchControls(
    input: InputCollector,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.align(Alignment.BottomStart)) {
            HoldButton(input, InputAction.Left, "Move left", Glyph.Left)
            Spacer(Modifier.size(16.dp))
            HoldButton(input, InputAction.Right, "Move right", Glyph.Right)
        }
        HoldButton(input, InputAction.Jump, "Jump", Glyph.Up, Modifier.align(Alignment.BottomEnd))
    }
}

private enum class Glyph { Left, Right, Up }

@Composable
private fun HoldButton(
    input: InputCollector,
    action: InputAction,
    label: String,
    glyph: Glyph,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(76.dp)
            .background(Color(0x55FFFFFF), CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(action) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    input.press(action)
                    waitForUpOrCancellation()
                    input.release(action)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().padding(22.dp)) {
            val w = size.width
            val h = size.height
            val path = Path()
            when (glyph) {
                Glyph.Left -> {
                    path.moveTo(w, 0f)
                    path.lineTo(0f, h / 2)
                    path.lineTo(w, h)
                }

                Glyph.Right -> {
                    path.moveTo(0f, 0f)
                    path.lineTo(w, h / 2)
                    path.lineTo(0f, h)
                }

                Glyph.Up -> {
                    path.moveTo(0f, h)
                    path.lineTo(w / 2, 0f)
                    path.lineTo(w, h)
                }
            }
            path.close()
            drawPath(path, Color.White.copy(alpha = 0.9f))
        }
    }
}
