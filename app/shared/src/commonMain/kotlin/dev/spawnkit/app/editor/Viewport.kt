package dev.spawnkit.app.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun Viewport(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pixelsPerUnit = with(LocalDensity.current) { UNIT_DP.dp.toPx().toDouble() }
    val ppu by rememberUpdatedState(pixelsPerUnit)
    val selectionPx = with(LocalDensity.current) { 2.5.dp.toPx() }
    val session = state.play
    if (session != null) {
        PlayLoop(session)
        // Reading frame subscribes to every presented frame.
        session.frame
        RuntimeCanvas(
            state.frame(session.scene),
            modifier.fillMaxSize().testTag("viewport"),
            camera = state.frame(session.scene).camera ?: state.camera,
            pixelsPerUnit = pixelsPerUnit.toFloat(),
        )
        return
    }
    // Rebuilt for every project revision, scene change and drag step: preview worlds are cheap and never shared.
    val world = remember(state.revision, state.sceneId, state.dragging) { state.previewWorld() }
    val frame = remember(world) { state.frame(world) }
    val selected = state.selection?.let(world::entity)
    val current by rememberUpdatedState(state)
    Box(
        modifier
            .fillMaxSize()
            .testTag("viewport")
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val s = current
                    val view = ViewTransform(s.camera, size.width.toDouble(), size.height.toDouble(), ppu)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (wx, wy) = view.toWorld(down.position.x.toDouble(), down.position.y.toDouble())
                    val built = s.previewWorld()
                    val hit = s.frame(built).hitTest(wx, wy)?.let(built::objectId)
                    var moved = false
                    var dragId: String? = if (hit != null && s.canMove(hit)) hit else null
                    var dragX = 0.0
                    var dragY = 0.0
                    if (hit != null) s.selection = hit
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: pinch-zoom around the centroid and pan; never moves objects.
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
                        if (event.type == PointerEventType.Scroll) {
                            val change = event.changes.first()
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
                    if (!session.scene.world.isFaulted) session.scene.world.step()
                    accumulator -= stepNanos
                }
                session.frame++
            }
        }
    }
}
