package dev.spawnkit.gameplay

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.CheckpointSystem
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.StateCaptureContext
import dev.spawnkit.runtime.StateCodec
import dev.spawnkit.runtime.StateRestoreContext
import dev.spawnkit.runtime.StateValue
import dev.spawnkit.runtime.World
import dev.spawnkit.runtime.WorldCommands
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A physics body: the engine moves it by its speed every moment, pulls it down by gravity, and stops it at objects
 * with [Solid]. Speeds are world units per second; y points down, so a negative [vy] moves up.
 */
public data class Body(
    /** Speed across; negative moves left. */
    public val vx: Double = 0.0,
    /** Speed down; negative moves up. */
    public val vy: Double = 0.0,
    /** How fast the speed down grows, in units per second each second; 0 for no gravity. */
    public val gravity: Double = DEFAULT_GRAVITY,
    /** Share of the speed kept when the body hits a solid: 0 stops it, 1 bounces back fully. */
    public val bounce: Double = 0.0,
    /** The fastest gravity makes the body fall. */
    public val maxFall: Double = DEFAULT_MAX_FALL,
    /** Whether the body stood on a solid after the last moment. Play state, not a property. */
    public val onGround: Boolean = false,
) {
    /** Default values. */
    public companion object {
        /** Default gravity. */
        public const val DEFAULT_GRAVITY: Double = 50.0

        /** Default fastest fall. */
        public const val DEFAULT_MAX_FALL: Double = 20.0
    }
}

/**
 * An object bodies cannot pass through, such as the ground, a wall or a platform. It remembers where it was after the
 * last moment, so bodies standing on it move with it, however it moved.
 */
public data class Solid(
    /** Whether bodies are stopped by it. */
    public val enabled: Boolean = true,
    /** Whether [lastX] and [lastY] hold a position yet. Play state, not a property. */
    public val tracked: Boolean = false,
    /** Centre of the object's box after the last moment. Play state, not a property. */
    public val lastX: Double = 0.0,
    /** Centre of the object's box after the last moment. Play state, not a property. */
    public val lastY: Double = 0.0,
)

/**
 * Keeps its object at the world position of the first object tagged [tag], across, down or both. On a camera, it
 * shows a level wider than the screen.
 */
public data class Follow(
    /** Tag of the object to follow. */
    public val tag: String = "player",
    /** Whether to follow across. */
    public val across: Boolean = true,
    /** Whether to follow up and down. */
    public val down: Boolean = true,
)

/**
 * The deterministic platform physics of [Body] and [Solid], once per tick after rules and steady movement. For each
 * body in scene order: ride the solid it stood on, get pushed out of solids it overlaps, gain gravity, then move across
 * and then down in short steps, stopping at solids. Only `+ - * /`, comparisons, `abs`, `min`, `max` and `floor`.
 */
internal class PhysicsSystem(
    private val gameplay: Gameplay,
    private val graphics: Graphics2D,
) : CheckpointSystem {
    private class Box(
        var left: Double,
        var top: Double,
        var right: Double,
        var bottom: Double,
    ) {
        fun overlaps(other: Bounds): Boolean =
            right - other.left > EPSILON && other.right - left > EPSILON && bottom - other.top > EPSILON &&
                other.bottom - top > EPSILON

        fun shift(
            dx: Double,
            dy: Double,
        ) {
            left += dx
            right += dx
            top += dy
            bottom += dy
        }
    }

    private class SolidBox(
        val entity: EntityHandle,
        val bounds: Bounds,
        val dx: Double,
        val dy: Double,
    )

    override fun update(
        world: World,
        commands: WorldCommands,
    ) {
        val solids =
            world.entities(gameplay.solid).mapNotNull { entity ->
                val solid = world.get(entity, gameplay.solid)?.takeIf { it.enabled } ?: return@mapNotNull null
                val box = gameplay.bounds(world, entity) ?: return@mapNotNull null
                val (x, y) = center(box)
                if (solid.tracked) SolidBox(entity, box, x - solid.lastX, y - solid.lastY) else SolidBox(entity, box, 0.0, 0.0)
            }
        for (entity in world.entities(gameplay.body, graphics.transform)) step(world, entity, solids)
        for (entity in world.entities(gameplay.solid)) {
            val solid = world.get(entity, gameplay.solid) ?: continue
            val box = gameplay.bounds(world, entity) ?: continue
            val (x, y) = center(box)
            world.set(entity, gameplay.solid, solid.copy(tracked = true, lastX = x, lastY = y))
        }
    }

    private fun step(
        world: World,
        entity: EntityHandle,
        allSolids: List<SolidBox>,
    ) {
        var body = world.get(entity, gameplay.body) ?: return
        val transform = world.get(entity, graphics.transform) ?: return
        val (scaleX, scaleY) = parentScale(world, entity)
        if (scaleX == 0.0 || scaleY == 0.0) return
        val start = gameplay.bounds(world, entity)
        var vx = body.vx
        var vy = body.vy
        var grounded = false
        var moveX = 0.0
        var moveY = 0.0
        if (start != null) {
            // The current position of other solids; a body that is itself solid never blocks itself.
            val solids = allSolids.filter { it.entity != entity }
            val own = Box(start.left, start.top, start.right, start.bottom)
            if (body.onGround) carry(own, solids)?.let { (dx, dy) -> own.shift(dx, dy) }
            for (solid in solids) {
                if (!own.overlaps(solid.bounds)) continue
                val b = solid.bounds
                val pushLeft = own.right - b.left
                val pushRight = b.right - own.left
                val pushUp = own.bottom - b.top
                val pushDown = b.bottom - own.top
                if (min(pushLeft, pushRight) < min(pushUp, pushDown)) {
                    own.shift(if (pushLeft < pushRight) -pushLeft else pushRight, 0.0)
                } else if (pushUp < pushDown) {
                    own.shift(0.0, -pushUp)
                    if (vy > 0.0) vy = 0.0
                    grounded = true
                } else {
                    own.shift(0.0, pushDown)
                    if (vy < 0.0) vy = 0.0
                }
            }
            vy = gravity(vy, body)
            val across = moveAxis(own, vx / TICKS, horizontal = true, solids)
            if (across) vx = rebound(vx, body)
            val down = moveAxis(own, vy / TICKS, horizontal = false, solids)
            if (down) {
                if (vy > 0.0) grounded = true
                vy = rebound(vy, body)
            }
            moveX = own.left - start.left
            moveY = own.top - start.top
        } else {
            vy = gravity(vy, body)
            moveX = vx / TICKS
            moveY = vy / TICKS
        }
        world.set(entity, graphics.transform, transform.copy(x = transform.x + moveX / scaleX, y = transform.y + moveY / scaleY))
        body = body.copy(vx = vx, vy = vy, onGround = grounded)
        world.set(entity, gameplay.body, body)
    }

    /** How far the solid under [own] moved since the last moment, or `null` when it stands on nothing that moved. */
    private fun carry(
        own: Box,
        solids: List<SolidBox>,
    ): Pair<Double, Double>? {
        for (solid in solids) {
            if (solid.dx == 0.0 && solid.dy == 0.0) continue
            val before = solid.bounds
            val top = before.top - solid.dy
            val left = before.left - solid.dx
            val right = before.right - solid.dx
            if (abs(own.bottom - top) <= CONTACT && own.right - left > EPSILON && right - own.left > EPSILON) {
                return solid.dx to solid.dy
            }
        }
        return null
    }

    private fun gravity(
        vy: Double,
        body: Body,
    ): Double {
        val pulled = vy + body.gravity / TICKS
        // Gravity never makes a body fall faster than its fastest fall; a faster push is kept.
        return if (body.gravity > 0.0 && pulled > body.maxFall) max(vy, body.maxFall) else pulled
    }

    private fun rebound(
        speed: Double,
        body: Body,
    ): Double {
        val back = -speed * body.bounce
        return if (abs(back) < STOP_SPEED + abs(body.gravity) / TICKS) 0.0 else back
    }

    /**
     * Moves [own] by [distance] along one axis in steps no longer than the body (or a quarter unit), so it cannot skip
     * a solid. Returns whether a solid stopped it; it then rests against the solid's edge.
     */
    private fun moveAxis(
        own: Box,
        distance: Double,
        horizontal: Boolean,
        solids: List<SolidBox>,
    ): Boolean {
        if (distance == 0.0) return false
        // Only solids in the swept area can stop the body.
        val swept =
            if (horizontal) {
                Bounds(min(own.left, own.left + distance), own.top, max(own.right, own.right + distance), own.bottom)
            } else {
                Bounds(own.left, min(own.top, own.top + distance), own.right, max(own.bottom, own.bottom + distance))
            }
        val near = solids.filter { Box(swept.left, swept.top, swept.right, swept.bottom).overlaps(it.bounds) }
        if (near.isEmpty()) {
            if (horizontal) own.shift(distance, 0.0) else own.shift(0.0, distance)
            return false
        }
        val size = if (horizontal) own.right - own.left else own.bottom - own.top
        val longest = min(MAX_STEP, max(size, MIN_STEP))
        val steps = min(MAX_STEPS, floor(abs(distance) / longest) + 1.0)
        val each = distance / steps
        var taken = 0.0
        while (taken < steps) {
            if (horizontal) own.shift(each, 0.0) else own.shift(0.0, each)
            val hits = near.filter { own.overlaps(it.bounds) }
            if (hits.isNotEmpty()) {
                if (horizontal) {
                    if (each > 0.0) own.shift(hits.minOf { it.bounds.left } - own.right, 0.0) else own.shift(hits.maxOf { it.bounds.right } - own.left, 0.0)
                } else {
                    if (each > 0.0) own.shift(0.0, hits.minOf { it.bounds.top } - own.bottom) else own.shift(0.0, hits.maxOf { it.bounds.bottom } - own.top)
                }
                return true
            }
            taken += 1.0
        }
        return false
    }

    /** The combined scale of [entity]'s parents, which turns world distances into its own units. */
    private fun parentScale(
        world: World,
        entity: EntityHandle,
    ): Pair<Double, Double> {
        var scaleX = 1.0
        var scaleY = 1.0
        var current = world.parent(entity)
        while (current != null) {
            world.get(current, graphics.transform)?.let {
                scaleX *= it.scaleX
                scaleY *= it.scaleY
            }
            current = world.parent(current)
        }
        return scaleX to scaleY
    }

    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

    override fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    ) = Unit

    private companion object {
        const val TICKS = 60.0

        /** Overlaps smaller than this are edges that meet, so a body resting on the ground can walk. */
        const val EPSILON = 1e-9

        /** How close a body's bottom must be to a platform's top to ride it. */
        const val CONTACT = 1e-6
        const val MAX_STEP = 0.25
        const val MIN_STEP = 0.01
        const val MAX_STEPS = 256.0

        /** Rebounds slower than this (plus one moment of gravity) stop, so bouncing bodies come to rest. */
        const val STOP_SPEED = 1.0

        fun center(box: Bounds): Pair<Double, Double> = (box.left + box.right) / 2 to (box.top + box.bottom) / 2
    }
}

/** Keeps objects with [Follow] at the object they follow, once per tick after physics. */
internal class FollowSystem(
    private val gameplay: Gameplay,
    private val graphics: Graphics2D,
) : CheckpointSystem {
    override fun update(
        world: World,
        commands: WorldCommands,
    ) {
        for (entity in world.entities(gameplay.follow, graphics.transform)) {
            val follow = world.get(entity, gameplay.follow) ?: continue
            if (!follow.across && !follow.down) continue
            val target = gameplay.tagged(world, follow.tag).firstOrNull { it != entity } ?: continue
            val (x, y) = origin(world, target)
            val parent = world.parent(entity)
            val (parentX, parentY, scaleX, scaleY) = parent?.let { originAndScale(world, it) } ?: listOf(0.0, 0.0, 1.0, 1.0)
            if (scaleX == 0.0 || scaleY == 0.0) continue
            val at = world.get(entity, graphics.transform) ?: continue
            world.set(
                entity,
                graphics.transform,
                at.copy(
                    x = if (follow.across) (x - parentX) / scaleX else at.x,
                    y = if (follow.down) (y - parentY) / scaleY else at.y,
                ),
            )
        }
    }

    private fun origin(
        world: World,
        entity: EntityHandle,
    ): Pair<Double, Double> = originAndScale(world, entity).let { it[0] to it[1] }

    /** World position and scale of [entity]'s own origin, from translation and scale up the parent chain. */
    private fun originAndScale(
        world: World,
        entity: EntityHandle,
    ): List<Double> {
        var x = 0.0
        var y = 0.0
        var scaleX = 1.0
        var scaleY = 1.0
        var current: EntityHandle? = entity
        while (current != null) {
            world.get(current, graphics.transform)?.let {
                x = it.x + it.scaleX * x
                y = it.y + it.scaleY * y
                scaleX *= it.scaleX
                scaleY *= it.scaleY
            }
            current = world.parent(current)
        }
        return listOf(x, y, scaleX, scaleY)
    }

    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

    override fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    ) = Unit
}

internal object BodyCodec : StateCodec<Body> {
    override fun capture(
        value: Body,
        context: StateCaptureContext,
    ): StateValue =
        StateValue.Record(
            mapOf(
                "vx" to StateValue.Decimal(value.vx),
                "vy" to StateValue.Decimal(value.vy),
                "gravity" to StateValue.Decimal(value.gravity),
                "bounce" to StateValue.Decimal(value.bounce),
                "maxFall" to StateValue.Decimal(value.maxFall),
                "onGround" to StateValue.Flag(value.onGround),
            ),
        )

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Body {
        val f = (state as StateValue.Record).fields
        return Body(
            decimal(f, "vx"),
            decimal(f, "vy"),
            decimal(f, "gravity"),
            decimal(f, "bounce"),
            decimal(f, "maxFall"),
            (f.getValue("onGround") as StateValue.Flag).value,
        )
    }
}

internal object SolidCodec : StateCodec<Solid> {
    override fun capture(
        value: Solid,
        context: StateCaptureContext,
    ): StateValue =
        StateValue.Record(
            mapOf(
                "enabled" to StateValue.Flag(value.enabled),
                "tracked" to StateValue.Flag(value.tracked),
                "lastX" to StateValue.Decimal(value.lastX),
                "lastY" to StateValue.Decimal(value.lastY),
            ),
        )

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Solid {
        val f = (state as StateValue.Record).fields
        return Solid(
            (f.getValue("enabled") as StateValue.Flag).value,
            (f.getValue("tracked") as StateValue.Flag).value,
            decimal(f, "lastX"),
            decimal(f, "lastY"),
        )
    }
}

internal object FollowCodec : StateCodec<Follow> {
    override fun capture(
        value: Follow,
        context: StateCaptureContext,
    ): StateValue =
        StateValue.Record(
            mapOf(
                "tag" to StateValue.Text(value.tag),
                "across" to StateValue.Flag(value.across),
                "down" to StateValue.Flag(value.down),
            ),
        )

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Follow {
        val f = (state as StateValue.Record).fields
        return Follow(
            (f.getValue("tag") as StateValue.Text).value,
            (f.getValue("across") as StateValue.Flag).value,
            (f.getValue("down") as StateValue.Flag).value,
        )
    }
}

private fun decimal(
    fields: Map<String, StateValue>,
    key: String,
): Double = (fields.getValue(key) as StateValue.Decimal).value
