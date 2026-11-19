package dev.spawnkit.engine

import dev.spawnkit.model.EntityId
import dev.spawnkit.model.Visual

/** Role flags describing which systems act on an entity. */
internal object Roles {
    const val PLAYER = 1
    const val COLLECTIBLE = 1 shl 1
    const val HAZARD = 1 shl 2
    const val PATROL = 1 shl 3
    const val CHECKPOINT = 1 shl 4
    const val GOAL = 1 shl 5
}

/**
 * Structure-of-arrays storage for entity state. Index `i` refers to the same entity in every array. Positions are the
 * entity's top-left corner; the collision box is `position + bodyOffset` with size `bodyWidth × bodyHeight`.
 */
internal class EntityStore(
    val ids: Array<EntityId>,
    val visuals: Array<Visual?>,
    val roles: IntArray,
    val x: DoubleArray,
    val y: DoubleArray,
    val bodyOffsetX: DoubleArray,
    val bodyOffsetY: DoubleArray,
    val bodyWidth: DoubleArray,
    val bodyHeight: DoubleArray,
    val collectVariable: IntArray,
    val collectAmount: DoubleArray,
    val patrolSpeed: DoubleArray,
    val patrolTurnAtEdges: BooleanArray,
) {
    val count: Int = ids.size
    val vx = DoubleArray(count)
    val vy = DoubleArray(count)
    val alive = BooleanArray(count) { true }
    val grounded = BooleanArray(count)
    val patrolDirection = DoubleArray(count) { 1.0 }
    val checkpointActivated = BooleanArray(count)

    fun has(
        i: Int,
        role: Int,
    ): Boolean = roles[i] and role != 0

    fun left(i: Int): Double = x[i] + bodyOffsetX[i]

    fun top(i: Int): Double = y[i] + bodyOffsetY[i]

    fun right(i: Int): Double = left(i) + bodyWidth[i]

    fun bottom(i: Int): Double = top(i) + bodyHeight[i]

    fun overlaps(
        a: Int,
        b: Int,
    ): Boolean = left(a) < right(b) && right(a) > left(b) && top(a) < bottom(b) && bottom(a) > top(b)
}
