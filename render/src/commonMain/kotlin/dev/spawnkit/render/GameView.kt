package dev.spawnkit.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import dev.spawnkit.engine.FixedStepClock
import dev.spawnkit.engine.GameEvent
import dev.spawnkit.engine.GameSession
import org.jetbrains.compose.resources.Font
import spawnkit.render.generated.resources.Res
import spawnkit.render.generated.resources.lilita_one

/**
 * Plays [session] inside a Compose layout: drives fixed simulation steps from frame timestamps, collects keyboard
 * (arrows/WASD, Space to jump) and touch input, and draws the world letterboxed to the game's virtual viewport.
 *
 * @param paused stops simulation while keeping the last frame on screen.
 * @param showTouchControls shows on-screen buttons (defaults to `true` on Android and iOS).
 * @param onEvents receives the events of every simulated tick (sound, UI, progress).
 */
@Composable
public fun GameView(
    session: GameSession,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    showTouchControls: Boolean = defaultShowTouchControls(),
    onEvents: (List<GameEvent>) -> Unit = {},
) {
    val input = remember(session, paused) { InputCollector() }
    val painter = remember(session) { WorldPainter() }
    val positions = remember(session) { RenderPositions() }
    val focus = remember { FocusRequester() }
    val currentOnEvents by rememberUpdatedState(onEvents)
    val currentPaused by rememberUpdatedState(paused)
    var frameTime by remember { mutableLongStateOf(0L) }
    var alpha by remember { mutableDoubleStateOf(1.0) }

    DisposableEffect(input) { onDispose { input.clear() } }
    LaunchedEffect(session, input) {
        val clock = FixedStepClock()
        while (true) {
            withFrameNanos { now ->
                if (currentPaused) {
                    clock.reset()
                    positions.capture(session.world)
                    alpha = 1.0
                } else {
                    for (step in 0 until clock.advance(now)) {
                        val world = session.world
                        positions.capture(world)
                        val events = session.step(input.frame())
                        if (GameEvent.PlayerDied in events) positions.capture(world)
                        if (events.isNotEmpty()) currentOnEvents(events)
                        if (session.world !== world) break
                    }
                    alpha = clock.alpha
                }
                frameTime = now
            }
        }
    }
    LaunchedEffect(input) { if (!paused) focus.requestFocus() }

    val fontFamily = FontFamily(Font(Res.font.lilita_one))
    Box(
        modifier
            .background(LetterboxColor)
            .semantics { contentDescription = "Game: ${session.project.meta.name}" }
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused) input.clear() }
            .onPreviewKeyEvent { !paused && input.onKeyEvent(it) }
            .focusable(enabled = !paused)
            .pointerInput(focus, paused) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!paused) focus.requestFocus()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading the frame state invalidates drawing even on frames with no simulation tick.
            frameTime.let { painter.draw(this, session.world, positions, alpha) }
        }
        Hud(session.world, frameTime, fontFamily, Modifier.align(Alignment.TopStart))
        if (showTouchControls && !paused) TouchControls(input)
    }
}

/** Whether on-screen touch controls are shown by default on this platform. */
public expect fun defaultShowTouchControls(): Boolean
