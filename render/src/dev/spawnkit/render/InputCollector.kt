package dev.spawnkit.render

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.spawnkit.engine.InputAction
import dev.spawnkit.engine.InputFrame

/** Collects keyboard and on-screen button state into the [InputFrame] read by each simulation tick. */
internal class InputCollector {
    private val heldKeys = mutableSetOf<Key>()
    private var touchBits = 0

    fun frame(): InputFrame = heldKeys.fold(InputFrame(touchBits)) { frame, key -> frame + checkNotNull(actionFor(key)) }

    /** Handles a key event; returns `true` if the key is a game control. */
    fun onKeyEvent(event: KeyEvent): Boolean =
        when (event.type) {
            KeyEventType.KeyDown -> setKey(event.key, true)
            KeyEventType.KeyUp -> setKey(event.key, false)
            else -> false
        }

    fun setKey(
        key: Key,
        down: Boolean,
    ): Boolean {
        if (actionFor(key) == null) return false
        if (down) heldKeys.add(key) else heldKeys.remove(key)
        return true
    }

    fun press(action: InputAction) {
        touchBits = touchBits or InputFrame.of(action).bits
    }

    fun release(action: InputAction) {
        touchBits = touchBits and InputFrame.of(action).bits.inv()
    }

    /** Releases all keys, e.g. when the game loses focus, so no key stays stuck. */
    fun clearKeys() {
        heldKeys.clear()
    }

    fun clear() {
        clearKeys()
        touchBits = 0
    }

    private fun actionFor(key: Key): InputAction? =
        when (key) {
            Key.DirectionLeft, Key.A -> InputAction.Left
            Key.DirectionRight, Key.D -> InputAction.Right
            Key.DirectionUp, Key.W, Key.Spacebar -> InputAction.Jump
            Key.DirectionDown, Key.S -> InputAction.Down
            Key.Enter, Key.E -> InputAction.Action
            else -> null
        }
}
