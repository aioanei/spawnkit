package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.graphics.ViewTransform
import dev.spawnkit.render.CanvasStyle
import dev.spawnkit.render.RuntimeCanvas

/** Density-independent size of one world unit at zoom 1, so scenes look the same size on phones and desktops. */
const val UNIT_DP = 48f

/**
 * The scene viewport. Gestures work with touch and with mouse/trackpad:
 * - tap/click selects the object under the pointer (empty space clears the selection);
 * - dragging a selected object moves it (one undo step when released);
 * - dragging empty space pans; two fingers pan and pinch-zoom; the mouse wheel zooms.
 *
 * Touching the viewport focuses it, which commits any half-typed inspector value and enables [shortcut] keys. Keys are
 * handled only while the viewport has focus, so typing in a text field never deletes or moves objects.
 */
@Composable
fun Viewport(
    state: EditorState,
    modifier: Modifier = Modifier,
    touchControls: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val current by rememberUpdatedState(state)
    // One focusable node for editing and play, so shortcuts such as Escape keep working when Play starts.
    Box(
        modifier
            .fillMaxSize()
            .testTag("viewport")
            .semantics { contentDescription = "Scene" }
            .focusRequester(focus)
            .onKeyEvent { current.shortcut(it) }
            .focusable(),
    ) {
        // Focus the scene when the builder opens and when play starts or stops, so keyboard users never lose it.
        LaunchedEffect(focus, state.play) { focus.requestFocus() }
        ViewportContent(state, focus, touchControls)
    }
}

@Composable
private fun ViewportContent(
    state: EditorState,
    focus: FocusRequester,
    touchControls: Boolean,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pixelsPerUnit = with(LocalDensity.current) { UNIT_DP.dp.toPx().toDouble() }
    val ppu by rememberUpdatedState(pixelsPerUnit)
    val selectionPx = with(LocalDensity.current) { 2.5.dp.toPx() }
    val session = state.play
    if (session != null) {
        PlayView(state, session, pixelsPerUnit, focus, touchControls)
        return
    }
    // Rebuilt for every project revision, scene change and drag step: preview worlds are cheap and never shared.
    val world = remember(state.revision, state.sceneId, state.dragging) { state.previewWorld() }
    val frame = remember(world) { state.frame(world) }
    val selected = state.selection?.let(world::entity)
    val current by rememberUpdatedState(state)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val s = current
                    val view = ViewTransform(s.camera, size.width.toDouble(), size.height.toDouble(), ppu)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    focus.requestFocus()
                    val (wx, wy) = view.toWorld(down.position.x.toDouble(), down.position.y.toDouble())
                    val built = s.previewWorld()
                    val hit = s.frame(built).hitTest(wx, wy)?.let(built::objectId)
                    var moved = false
                    var dragId: String? = if (hit != null && s.canMove(hit)) hit else null
                    var dragX = 0.0
                    var dragY = 0.0
                    val previousSelection = s.selection
                    if (hit != null) s.selection = hit
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: pinch-zoom around the centroid and pan; never moves or selects objects.
                            if (dragId != null || s.selection != previousSelection) s.selection = previousSelection
                            dragId = null
                            s.dragging = null
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            s.camera = zoomAround(s, size, ppu, centroid, zoom, pan)
                            moved = true
                        } else {
                            val delta = pressed.first().positionChange()
                            if (delta != Offset.Zero) {
                                moved = true
                                val scale = ppu * s.camera.zoom
                                val id = dragId
                                if (id != null) {
                                    val (lx, ly) = s.localDelta(built, id, delta.x / scale, delta.y / scale)
                                    dragX += lx
                                    dragY += ly
                                    s.dragging = id to (dragX to dragY)
                                } else {
                                    s.camera =
                                        s.camera.copy(
                                            centerX = s.camera.centerX - delta.x / scale,
                                            centerY =
                                                s.camera.centerY - delta.y / scale,
                                        )
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    val id = dragId
                    if (id != null && (dragX != 0.0 || dragY != 0.0)) {
                        s.commitMove(id, dragX, dragY)
                    } else {
                        s.dragging = null
                        if (!moved && hit == null) s.selection = null
                    }
                }
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        // Horizontal-only trackpad scrolls carry no zoom intent.
                        if (event.type == PointerEventType.Scroll && change.scrollDelta.y != 0f) {
                            val factor = if (change.scrollDelta.y > 0) 1 / 1.15f else 1.15f
                            current.camera = zoomAround(current, size, ppu, change.position, factor, Offset.Zero)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        RuntimeCanvas(
            frame,
            Modifier.fillMaxSize(),
            camera = state.camera,
            pixelsPerUnit = pixelsPerUnit.toFloat(),
            selection = setOfNotNull(selected),
            style = CanvasStyle(showGrid = true, selectionWidthPx = selectionPx),
        )
    }
}

/**
 * The running game. Pointers are converted to world units through the camera the player sees and delivered to the
 * play world, so rules can use `tapped()`; on-screen controls hold the standard actions on touch screens.
 */
@Composable
private fun PlayView(
    state: EditorState,
    session: PlaySession,
    pixelsPerUnit: Double,
    focus: FocusRequester,
    touchControls: Boolean,
) {
    PlayLoop(session)
    // Reading frame subscribes to every presented frame.
    session.frame
    val frame = state.frame(session.scene)
    val camera = frame.camera ?: state.camera
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
                                        focus.requestFocus()
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
        )
        if (touchControls) {
            TouchControls(session, Modifier.align(Alignment.BottomCenter))
        }
    }
}

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

/** Distance moved by an arrow key, and by Shift+arrow, in world units. */
private const val NUDGE = 0.1
private const val NUDGE_LARGE = 1.0

/**
 * Builder keyboard shortcuts. Ctrl and Cmd are interchangeable: undo (Z), redo (Shift+Z or Y), duplicate (D); Delete or
 * Backspace removes the selection; arrows nudge it (Shift for whole units); Escape clears the selection or stops play.
 */
internal fun EditorState.shortcut(event: KeyEvent): Boolean {
    play?.let { session ->
        // While playing, keys hold the standard actions: arrows/WASD move, Space jumps, Escape stops.
        val action = PlayKeys[event.key]
        when {
            action != null && event.type == KeyEventType.KeyDown -> session.press(action, "keyboard:${event.key}")
            action != null && event.type == KeyEventType.KeyUp -> session.release(action, "keyboard:${event.key}")
            event.key == Key.Escape && event.type == KeyEventType.KeyDown -> stopPlay()
            else -> return false
        }
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    val command = event.isCtrlPressed || event.isMetaPressed
    val step = if (event.isShiftPressed) NUDGE_LARGE else NUDGE
    when {
        command && event.key == Key.Z && event.isShiftPressed -> redo()
        command && event.key == Key.Z -> undo()
        command && event.key == Key.Y -> redo()
        command && event.key == Key.D -> duplicateSelection()
        command -> return false
        event.key == Key.Delete || event.key == Key.Backspace -> deleteSelection()
        event.key == Key.Escape -> selection = null
        event.key == Key.DirectionLeft -> nudgeSelection(-step, 0.0)
        event.key == Key.DirectionRight -> nudgeSelection(step, 0.0)
        event.key == Key.DirectionUp -> nudgeSelection(0.0, -step)
        event.key == Key.DirectionDown -> nudgeSelection(0.0, step)
        else -> return false
    }
    return true
}

/** Zooms by [factor] keeping the world point under [focus] fixed, then pans by [pan] pixels. */
private fun zoomAround(
    state: EditorState,
    size: IntSize,
    pixelsPerUnit: Double,
    focus: Offset,
    factor: Float,
    pan: Offset,
): dev.spawnkit.graphics.CameraView {
    val before = ViewTransform(state.camera, size.width.toDouble(), size.height.toDouble(), pixelsPerUnit)
    val (fx, fy) = before.toWorld(focus.x.toDouble(), focus.y.toDouble())
    val zoom = (state.camera.zoom * factor).coerceIn(0.1, 10.0)
    val scale = pixelsPerUnit * zoom
    // Keep (fx, fy) under the focus point after zooming, then apply the pan.
    val centerX = fx - (focus.x - size.width / 2.0) / scale - pan.x / scale
    val centerY = fy - (focus.y - size.height / 2.0) / scale - pan.y / scale
    return state.camera.copy(centerX = centerX, centerY = centerY, zoom = zoom)
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
                    }
                    accumulator -= stepNanos
                }
                session.frame++
            }
        }
    }
}
