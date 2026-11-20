package dev.spawnkit.engine

import dev.spawnkit.model.GameProject
import dev.spawnkit.model.SceneId

/**
 * Plays a [GameProject]: owns the current [RuntimeWorld], advances it one fixed step at a time and moves between
 * scenes. Variables carry over from one scene to the next; restarting a level restores the values it started with.
 *
 * @throws IllegalArgumentException if [startScene] does not compile (validate projects before playing them).
 */
public class GameSession(
    /** The project being played. */
    public val project: GameProject,
    startScene: SceneId = project.settings.startScene,
) {
    /** The world of the current scene. Replaced on restart and scene changes. */
    public var world: RuntimeWorld = compileOrThrow(startScene)
        private set

    private var levelStartVariables: DoubleArray = world.variables.copyOf()

    /** Index of the current scene in [GameProject.scenes]. */
    public val sceneIndex: Int get() = project.scenes.indexOfFirst { it.id == world.scene.id }

    /** Advances the simulation by one tick of [DT] seconds and returns what happened. No-op after level completion. */
    public fun step(input: InputFrame): List<GameEvent> {
        val current = world
        if (current.levelCompleted) return emptyList()
        current.tick++
        val events = mutableListOf<GameEvent>()
        PlatformerSystem.step(current, input)
        PatrolSystem.step(current)
        InteractionSystem.step(current, events)
        return events
    }

    /** Restarts the current scene with the variable values it started with. */
    public fun restartLevel() {
        world = compileOrThrow(world.scene.id, levelStartVariables)
    }

    /** Moves to the next scene, carrying variables over. Returns `false` if this was the last scene. */
    public fun advanceToNextScene(): Boolean {
        val next = project.scenes.getOrNull(sceneIndex + 1) ?: return false
        val carried = world.variables.copyOf()
        world = compileOrThrow(next.id, carried)
        levelStartVariables = carried.copyOf()
        return true
    }

    /** Deterministic hash of the current world state; see [RuntimeWorld.stateHash]. */
    public fun stateHash(): Long = world.stateHash()

    private fun compileOrThrow(
        sceneId: SceneId,
        variables: DoubleArray? = null,
    ): RuntimeWorld =
        when (val result = WorldCompiler.compile(project, sceneId)) {
            is CompileResult.Ok -> result.world.also { compiled -> variables?.copyInto(compiled.variables) }
            is CompileResult.Failed -> throw IllegalArgumentException(result.errors.joinToString("; "))
        }
}
