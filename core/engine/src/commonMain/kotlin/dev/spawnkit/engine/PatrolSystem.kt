package dev.spawnkit.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Moves patrolling enemies back and forth, turning at walls and (optionally) at ledges. */
internal object PatrolSystem {
    fun step(world: RuntimeWorld) {
        val s = world.entities
        for (i in 0 until s.count) {
            if (!s.alive[i] || !s.has(i, Roles.PATROL)) continue
            s.vy[i] = min(s.vy[i] + world.physics.gravity * DT, world.physics.maxFallSpeed)
            val dx = s.patrolDirection[i] * s.patrolSpeed[i] * DT
            if (s.patrolTurnAtEdges[i] && s.grounded[i] && !groundAhead(world, i, abs(dx))) {
                s.patrolDirection[i] = -s.patrolDirection[i]
            }
            s.vx[i] = s.patrolDirection[i] * s.patrolSpeed[i]
            if (TilePhysics.moveX(world, i, s.vx[i] * DT)) {
                s.patrolDirection[i] = -s.patrolDirection[i]
            }
            TilePhysics.moveY(world, i, s.vy[i] * DT)
            if (s.top(i) > world.grid.height + FALL_OUT_MARGIN) s.alive[i] = false
        }
    }

    private fun groundAhead(
        world: RuntimeWorld,
        i: Int,
        step: Double,
    ): Boolean {
        val s = world.entities
        val probe = max(LEDGE_PROBE, step)
        val probeX = if (s.patrolDirection[i] > 0.0) s.right(i) + probe else s.left(i) - probe
        val rowBelowFeet = floorInt(s.bottom(i) + EPSILON)
        return world.grid.supportsFromAbove(floorInt(probeX), rowBelowFeet)
    }
}
