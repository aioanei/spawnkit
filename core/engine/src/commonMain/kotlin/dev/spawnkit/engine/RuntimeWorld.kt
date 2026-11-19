package dev.spawnkit.engine

import dev.spawnkit.model.EntityId
import dev.spawnkit.model.GameProject
import dev.spawnkit.model.PhysicsSettings
import dev.spawnkit.model.PlayerController
import dev.spawnkit.model.Scene
import dev.spawnkit.model.TileBehavior
import dev.spawnkit.model.VariableId
import dev.spawnkit.model.Visual

/** Mutable state of the player character that is not stored per entity. */
internal class PlayerState(
    val index: Int,
    val controller: PlayerController,
    val spawnX: Double,
    val spawnY: Double,
    val deathCounter: Int,
) {
    var respawnX: Double = spawnX
    var respawnY: Double = spawnY
    var ticksSinceGrounded: Int = controller.coyoteTicks + 1
    var jumpBuffer: Int = 0
    var jumpWasDown: Boolean = false
    var jumpedSinceGround: Boolean = false
    var jumpCut: Boolean = false
    var previousBottom: Double = 0.0
}

/**
 * The running state of one scene, produced by [WorldCompiler] and advanced by [GameSession.step].
 * The public API is read-only; renderers, validators and tests observe it, only the systems mutate it.
 */
public class RuntimeWorld internal constructor(
    /** The project this world was compiled from. */
    public val project: GameProject,
    /** The scene this world simulates. */
    public val scene: Scene,
    internal val grid: TileGrid,
    internal val entities: EntityStore,
    internal val variableIndex: Map<VariableId, Int>,
    internal val variables: DoubleArray,
    internal val player: PlayerState,
) {
    /** Number of simulation steps taken. */
    public var tick: Long = 0
        internal set

    /** Whether the player reached a goal; the world no longer changes afterwards. */
    public var levelCompleted: Boolean = false
        internal set

    internal val physics: PhysicsSettings get() = project.settings.physics

    /** Number of entities, alive or not. Indices are stable for the lifetime of the world. */
    public val entityCount: Int get() = entities.count

    /** Index of the player entity. */
    public val playerIndex: Int get() = player.index

    /** ID of entity [i]. */
    public fun entityId(i: Int): EntityId = entities.ids[i]

    /** Index of the entity with [id], or -1. */
    public fun indexOf(id: EntityId): Int = entities.ids.indexOf(id)

    /** Left edge of entity [i] in tiles. */
    public fun x(i: Int): Double = entities.x[i]

    /** Top edge of entity [i] in tiles. */
    public fun y(i: Int): Double = entities.y[i]

    /** Horizontal velocity of entity [i] in tiles per second. */
    public fun vx(i: Int): Double = entities.vx[i]

    /** Vertical velocity of entity [i] in tiles per second (positive is downwards). */
    public fun vy(i: Int): Double = entities.vy[i]

    /** Collision box width of entity [i]. */
    public fun bodyWidth(i: Int): Double = entities.bodyWidth[i]

    /** Collision box height of entity [i]. */
    public fun bodyHeight(i: Int): Double = entities.bodyHeight[i]

    /** Whether entity [i] still exists (collected coins and defeated enemies do not). */
    public fun isAlive(i: Int): Boolean = entities.alive[i]

    /** Whether entity [i] stood on the ground after the last step. */
    public fun isGrounded(i: Int): Boolean = entities.grounded[i]

    /** How entity [i] is drawn, if it is visible. */
    public fun visual(i: Int): Visual? = entities.visuals[i]

    /** Current value of [id]; 0.0 if the project does not define it. */
    public fun variable(id: VariableId): Double = variableIndex[id]?.let { variables[it] } ?: 0.0

    /** Merged behavior of the tile at column [x], row [y] (walls beyond the sides, open above and below). */
    public fun tileBehavior(
        x: Int,
        y: Int,
    ): TileBehavior = grid.behavior(x, y)
}
