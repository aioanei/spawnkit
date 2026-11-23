package dev.spawnkit.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.runtime.InputAction
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.Pointer
import dev.spawnkit.runtime.World

/**
 * A running play session: an isolated world built from a scene, plus the input the player is giving it. Keyboard
 * keys, on-screen controls and pointers are combined into one [InputFrame] per tick. The builder's Play mode and
 * exported players share this class, so a released game behaves exactly as it did while it was being made.
 *
 * After each tick, [afterTick] asks [sceneRequest] whether a rule asked for another scene ("Go to scene") and then
 * switches to a fresh world from [buildScene]. Create sessions with [StandardEngine.playSession].
 */
class PlaySession(
    scene: SceneWorld,
    private val actions: Map<String, InputAction>,
    startSceneId: String? = null,
    private val sceneRequest: (world: World, sceneId: String?) -> String? = { _, _ -> null },
    private val buildScene: (String) -> SceneWorld? = { null },
    private val onSceneChange: (from: World, to: World) -> Unit = { _, _ -> },
) {
    /** The world being played; replaced when the game goes to another scene. */
    var scene by mutableStateOf(scene)
        private set

    /** ID of the scene being played, when known. */
    var sceneId by mutableStateOf(startSceneId)
        private set

    /** A plain-language problem with the last scene change, or `null`. */
    var message by mutableStateOf<String?>(null)
        private set

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

    /**
     * Acts on a "Go to scene" request from the tick that just ran: the game continues in a fresh world of that scene.
     * Pointers pressed on the old scene are dropped, so a held finger does not tap the new scene.
     */
    fun afterTick() {
        val requested = sceneRequest(scene.world, sceneId) ?: return
        val next = buildScene(requested)
        if (next == null) {
            message = "A rule asked for a scene that no longer exists."
            return
        }
        onSceneChange(scene.world, next.world)
        scene = next
        sceneId = requested
        message = null
        pointers.clear()
        unsubmitted.clear()
        releaseAfterSubmit.clear()
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

/**
 * A play session of [project] starting at scene [sceneId], which follows "Go to scene" blocks to fresh worlds of the
 * same project snapshot, carrying numbers marked "Keep across scenes" and making the copies "Spawn a copy" blocks ask
 * for (C14). Template objects are left out until spawned. The builder's Play and exported players both use it.
 */
fun StandardEngine.playSession(
    project: Project,
    sceneId: String,
): PlaySession {
    val kept = mutableMapOf<String, Long>()
    val played = playProject(project)
    return PlaySession(
        buildSceneWorld(runtime, played, sceneId, programs),
        actions,
        sceneId,
        sceneRequest = { world, current -> handleRequests(world, project, current ?: sceneId) },
        buildScene = { id -> played.scene(id)?.let { buildSceneWorld(runtime, played, it.id, programs) } },
        onSceneChange = { from, to -> carryKeptNumbers(from, to, kept) },
    )
}
