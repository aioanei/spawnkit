package dev.spawnkit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.graphics.CameraView
import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.graphics.ViewTransform
import dev.spawnkit.graphics.renderList
import dev.spawnkit.render.RuntimeCanvas

/**
 * The running game. Pointers are converted to world units through the camera the player sees and delivered to the
 * play world, so rules can use `tapped()`; on-screen controls hold the standard actions on touch screens. The scene's
 * own camera wins; [fallbackCamera] is used when the scene has none.
 */
@Composable
fun PlaySurface(
    session: PlaySession,
    graphics: Graphics2D,
    fallbackCamera: CameraView,
    pixelsPerUnit: Double,
    touchControls: Boolean,
    focus: FocusRequester? = null,
    images: Map<String, ImageBitmap> = emptyMap(),
) {
    PlayLoop(session)
    // Reading frame subscribes to every presented frame.
    session.frame
    val frame = graphics.renderList(session.scene.world)
    val camera = frame.camera ?: fallbackCamera
    val view by rememberUpdatedState(camera)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val ids = remember(session) { mutableMapOf<Long, Int>() }
    Box(Modifier.fillMaxSize()) {
        RuntimeCanvas(
            frame,
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(session) {
                    awaitPointerEventScope {
                        var next = 1
                        while (true) {
                            val event = awaitPointerEvent()
                            val transform = ViewTransform(view, size.width.toDouble(), size.height.toDouble(), pixelsPerUnit)
                            for (change in event.changes) {
                                val id = ids.getOrPut(change.id.value) { next++ }
                                val (x, y) = transform.toWorld(change.position.x.toDouble(), change.position.y.toDouble())
                                when {
                                    change.pressed && !change.previousPressed -> {
                                        focus?.requestFocus()
                                        session.pointerDown(id, x, y)
                                    }

                                    change.pressed -> {
                                        session.pointerMoved(id, x, y)
                                    }

                                    change.previousPressed -> {
                                        session.pointerUp(id)
                                        ids -= change.id.value
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                },
            camera = camera,
            pixelsPerUnit = pixelsPerUnit.toFloat(),
            images = images,
        )
        if (touchControls) {
            TouchControls(session, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Applies a play key to [this] session: arrows/WASD hold the movement actions and Space holds jump. Returns `false`
 * for keys that are not play keys, so hosts can handle them (the builder stops play on Escape).
 */
fun PlaySession.handlePlayKey(event: KeyEvent): Boolean {
    val action = PlayKeys[event.key] ?: return false
    when (event.type) {
        KeyEventType.KeyDown -> press(action, "keyboard:${event.key}")
        KeyEventType.KeyUp -> release(action, "keyboard:${event.key}")
        else -> return false
    }
    return true
}

/** Keys that hold the standard actions while playing. */
private val PlayKeys: Map<Key, String> =
    mapOf(
        Key.DirectionLeft to StandardActions.LEFT,
        Key.A to StandardActions.LEFT,
        Key.DirectionRight to StandardActions.RIGHT,
        Key.D to StandardActions.RIGHT,
        Key.DirectionUp to StandardActions.UP,
        Key.W to StandardActions.UP,
        Key.DirectionDown to StandardActions.DOWN,
        Key.S to StandardActions.DOWN,
        Key.Spacebar to StandardActions.JUMP,
    )

/** Large hold-to-press buttons for the standard actions, for phones and tablets without a keyboard. */
@Composable
private fun TouchControls(
    session: PlaySession,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton(session, StandardActions.LEFT, "◀", "Left")
            ControlButton(session, StandardActions.RIGHT, "▶", "Right")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton(session, StandardActions.UP, "▲", "Up")
                ControlButton(session, StandardActions.DOWN, "▼", "Down")
            }
            ControlButton(session, StandardActions.JUMP, "⤒", "Jump")
        }
    }
}

@Composable
private fun ControlButton(
    session: PlaySession,
    actionId: String,
    symbol: String,
    description: String,
) {
    Box(
        Modifier
            .size(64.dp)
            .background(Color.White.copy(alpha = 0.18f), CircleShape)
            .semantics { contentDescription = description }
            .testTag("control:$actionId")
            .pointerInput(session) {
                detectTapGestures(onPress = {
                    session.press(actionId, "touch")
                    try {
                        tryAwaitRelease()
                    } finally {
                        session.release(actionId, "touch")
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Color.White, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Steps the play world at a fixed 60 Hz, independent of the display refresh rate. */
@Composable
private fun PlayLoop(session: PlaySession) {
    LaunchedEffect(session) {
        val stepNanos = 1_000_000_000L / 60
        var last = withFrameNanos { it }
        var accumulator = 0L
        while (true) {
            withFrameNanos { now ->
                accumulator += (now - last).coerceAtMost(250_000_000L)
                last = now
                while (accumulator >= stepNanos) {
                    if (!session.scene.world.isFaulted) {
                        session.scene.world.submitInput(session.nextInput())
                        session.scene.world.step()
                        session.afterTick()
                    }
                    accumulator -= stepNanos
                }
                session.frame++
            }
        }
    }
}
