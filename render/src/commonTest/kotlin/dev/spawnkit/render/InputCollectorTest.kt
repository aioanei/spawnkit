package dev.spawnkit.render

import androidx.compose.ui.input.key.Key
import dev.spawnkit.engine.InputAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputCollectorTest {
    @Test
    fun releasingOneJumpKeyKeepsOtherJumpKeyHeld() {
        val input = InputCollector()
        input.setKey(Key.Spacebar, true)
        input.setKey(Key.W, true)
        input.setKey(Key.Spacebar, false)
        assertTrue(input.frame().isDown(InputAction.Jump))
        input.setKey(Key.W, false)
        assertFalse(input.frame().isDown(InputAction.Jump))
    }

    @Test
    fun keyRepeatDoesNotLeaveActionStuck() {
        val input = InputCollector()
        repeat(3) { input.setKey(Key.D, true) }
        input.setKey(Key.D, false)
        assertFalse(input.frame().isDown(InputAction.Right))
    }

    @Test
    fun keyboardAndTouchReleaseIndependently() {
        val input = InputCollector()
        input.setKey(Key.A, true)
        input.press(InputAction.Left)
        input.clearKeys()
        assertTrue(input.frame().isDown(InputAction.Left))
        input.release(InputAction.Left)
        assertFalse(input.frame().isDown(InputAction.Left))
    }

    @Test
    fun simultaneousMovementAndJumpSurviveClearing() {
        val input = InputCollector()
        input.setKey(Key.DirectionRight, true)
        input.press(InputAction.Jump)
        assertTrue(input.frame().isDown(InputAction.Right))
        assertTrue(input.frame().isDown(InputAction.Jump))
        input.clear()
        assertFalse(input.frame().isDown(InputAction.Right))
        assertFalse(input.frame().isDown(InputAction.Jump))
    }
}
