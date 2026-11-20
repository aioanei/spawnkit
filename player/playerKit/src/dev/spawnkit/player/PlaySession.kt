package dev.spawnkit.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.runtime.InputAction
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.Pointer

/**
 * A running play session: an isolated world built from a scene, plus the input the player is giving it. Keyboard
 * keys, on-screen controls and pointers are combined into one [InputFrame] per tick. The builder's Play mode and
 * exported players share this class, so a released game behaves exactly as it did while it was being made.
 */
class PlaySession(
    val scene: SceneWorld,
    private val actions: Map<String, InputAction>,
) {
    /** Incremented after every presented frame so the play surface redraws. */
    var frame by mutableLongStateOf(0L)

    private val held = mutableMapOf<String, MutableSet<String>>()
    private val pointers = LinkedHashMap<Int, Pointer>()
    private val unsubmitted = mutableSetOf<Int>()
    private val releaseAfterSubmit = mutableSetOf<Int>()

    /** Starts holding action [actionId] from [source] (for example "keyboard" or "touch"). */
    fun press(
        actionId: String,
        source: String,
    ) {
        if (actionId in actions) held.getOrPut(actionId) { mutableSetOf() } += source
    }

    /** Stops holding action [actionId] from [source]; the action stays held while another source holds it. */
    fun release(
        actionId: String,
        source: String,
    ) {
        held[actionId]?.let {
            it -= source
            if (it.isEmpty()) held -= actionId
        }
    }

    /** A pointer went down at world position ([x], [y]). */
    fun pointerDown(
        id: Int,
        x: Double,
        y: Double,
    ) {
        pointers[id] = Pointer(id, x, y, pressed = true)
        unsubmitted += id
        releaseAfterSubmit -= id
    }

    /** A pressed pointer moved to world position ([x], [y]). */
    fun pointerMoved(
        id: Int,
        x: Double,
        y: Double,
    ) {
        if (id in pointers) pointers[id] = Pointer(id, x, y, pressed = true)
    }

    /** A pointer was lifted. A press no tick has seen yet is still delivered once, so quick taps are never lost. */
    fun pointerUp(id: Int) {
        if (id in unsubmitted) releaseAfterSubmit += id else pointers -= id
    }

    /** The input for the next tick. */
    fun nextInput(): InputFrame {
        val frame = InputFrame(down = held.keys.mapNotNull(actions::get).toSet(), pointers = pointers.values.toList())
        unsubmitted.clear()
        releaseAfterSubmit.forEach { pointers -= it }
        releaseAfterSubmit.clear()
        return frame
    }
}
