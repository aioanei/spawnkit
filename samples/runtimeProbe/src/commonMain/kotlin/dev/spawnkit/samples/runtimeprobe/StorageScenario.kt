package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.CheckpointSystem
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCaptureContext
import dev.spawnkit.runtime.StateCodec
import dev.spawnkit.runtime.StateCodecs
import dev.spawnkit.runtime.StateRestoreContext
import dev.spawnkit.runtime.StateValue
import dev.spawnkit.runtime.World
import dev.spawnkit.runtime.WorldCommands

/** Mutable position of a probe entity, in arbitrary integer units. */
public class Position(
    /** Horizontal coordinate. */
    public var x: Long,
    /** Vertical coordinate. */
    public var y: Long,
)

/** Constant per-tick displacement applied to a [Position]. */
public class Velocity(
    /** Horizontal displacement per tick. */
    public var dx: Long,
    /** Vertical displacement per tick. */
    public var dy: Long,
)

/** A reference to another entity, which can become stale when that entity is destroyed. */
public class Link(
    /** Referenced entity handle; may be stale. */
    public val target: EntityHandle,
)

/**
 * Storage comparison fixture installed through the public runtime API only. It exercises dense and sparse component
 * sets, multi-component queries, per-tick structural churn through [WorldCommands], stale entity references and
 * private system state. Query order is folded into private state, so any ordering change alters [World.stateHash].
 *
 * All randomness comes from a seeded 64-bit LCG using wrapping integer arithmetic, identical on every target.
 */
public class StorageScenario(
    builder: RuntimeBuilder,
    /** Seed for the churn system's private generator. */
    seed: Long = 0x5EED_CAFEL,
    /** Structural operations recorded per tick by the churn system; zero disables churn. */
    internal val churnPerTick: Int = 8,
) {
    /** Dense component present on most entities. */
    public val position: ComponentType<Position> =
        builder.component("probe:position", AuthoringInfo("Position", category = "Examples"), PositionCodec)

    /** Component present on a moving subset. */
    public val velocity: ComponentType<Velocity> =
        builder.component("probe:velocity", AuthoringInfo("Velocity", category = "Examples"), VelocityCodec)

    /** Sparse integer marker, frequently attached and removed. */
    public val marker: ComponentType<Int> =
        builder.component("probe:marker", AuthoringInfo("Marker", category = "Examples"), StateCodecs.int)

    /** Sparse entity reference, retained after its target is destroyed. */
    public val link: ComponentType<Link> =
        builder.component("probe:link", AuthoringInfo("Link", category = "Examples"), LinkCodec)

    init {
        builder.system("probe:motion") { MotionSystem(this) }
        builder.system("probe:churn", after = setOf("probe:motion")) { ChurnSystem(this, seed) }
    }

    /** Spawns [count] entities outside a tick with a deterministic mix of components. */
    public fun populate(
        world: World,
        count: Int,
    ) {
        repeat(count) { i -> configure(world, world.spawn(), i.toLong()) }
    }

    internal fun configure(
        world: World,
        entity: EntityHandle,
        value: Long,
    ) {
        world.set(entity, position, Position(value, -value))
        if (value % 3L != 0L) world.set(entity, velocity, Velocity(1 + value % 5L, 2 - value % 7L))
        if (value % 4L == 1L) world.set(entity, marker, (value % 1_000L).toInt())
    }
}

/** Moves every positioned entity with a velocity and folds visited slot order into private state. */
private class MotionSystem(
    private val scenario: StorageScenario,
) : CheckpointSystem {
    private var orderChecksum = 0L

    override fun update(
        world: World,
        commands: WorldCommands,
    ) {
        for (entity in world.entities(scenario.position, scenario.velocity)) {
            val position = checkNotNull(world.get(entity, scenario.position))
            val velocity = checkNotNull(world.get(entity, scenario.velocity))
            position.x += velocity.dx
            position.y += velocity.dy
            orderChecksum = orderChecksum * 31L + entity.index
        }
        for (entity in world.entities(scenario.marker)) {
            orderChecksum = orderChecksum * 17L + checkNotNull(world.get(entity, scenario.marker))
        }
    }

    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Integer(orderChecksum)

    override fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    ) {
        orderChecksum = StateCodecs.long.restore(state, context)
    }
}

/** Records seeded structural edits each tick: spawn, destroy, attach/remove marker, link and velocity removal. */
private class ChurnSystem(
    private val scenario: StorageScenario,
    private var random: Long,
) : CheckpointSystem {
    override fun update(
        world: World,
        commands: WorldCommands,
    ) {
        val operations = scenario.churnPerTick
        if (operations == 0) return
        val live = world.entities()
        repeat(operations) {
            val choice = next()
            val pick = if (live.isEmpty()) null else live[next() % live.size]
            when (choice % 6) {
                0 -> {
                    val value = next().toLong()
                    commands.spawn { scenario.configure(this, it, value) }
                }

                1 -> {
                    pick?.let(commands::destroy)
                }

                2 -> {
                    pick?.let { commands.set(it, scenario.marker, next() % 1_000) }
                }

                3 -> {
                    pick?.let { commands.remove(it, scenario.marker) }
                }

                4 -> {
                    if (pick != null && live.isNotEmpty()) commands.set(pick, scenario.link, Link(live[next() % live.size]))
                }

                else -> {
                    pick?.let { commands.remove(it, scenario.velocity) }
                }
            }
        }
    }

    /** Non-negative 31-bit value from a 64-bit LCG (Knuth MMIX constants). */
    private fun next(): Int {
        random = random * 6364136223846793005L + 1442695040888963407L
        return (random ushr 33).toInt()
    }

    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Integer(random)

    override fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    ) {
        random = StateCodecs.long.restore(state, context)
    }
}

private object PositionCodec : StateCodec<Position> {
    override fun capture(
        value: Position,
        context: StateCaptureContext,
    ): StateValue = StateValue.Sequence(listOf(StateValue.Integer(value.x), StateValue.Integer(value.y)))

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Position {
        val (x, y) = longPair(state, context)
        return Position(x, y)
    }
}

private object VelocityCodec : StateCodec<Velocity> {
    override fun capture(
        value: Velocity,
        context: StateCaptureContext,
    ): StateValue = StateValue.Sequence(listOf(StateValue.Integer(value.dx), StateValue.Integer(value.dy)))

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Velocity {
        val (dx, dy) = longPair(state, context)
        return Velocity(dx, dy)
    }
}

private object LinkCodec : StateCodec<Link> {
    override fun capture(
        value: Link,
        context: StateCaptureContext,
    ): StateValue = context.entity(value.target)

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Link = Link(context.entity(state))
}

private fun longPair(
    state: StateValue,
    context: StateRestoreContext,
): Pair<Long, Long> {
    require(state is StateValue.Sequence && state.values.size == 2) { "Expected a pair of integers" }
    return StateCodecs.long.restore(state.values[0], context) to StateCodecs.long.restore(state.values[1], context)
}
