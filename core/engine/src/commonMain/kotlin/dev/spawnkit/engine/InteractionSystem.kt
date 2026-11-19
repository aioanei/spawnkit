package dev.spawnkit.engine

import dev.spawnkit.model.TileBehavior

/** Resolves player contact with hazards, pickups, enemies, checkpoints and goals. */
internal object InteractionSystem {
    fun step(
        world: RuntimeWorld,
        events: MutableList<GameEvent>,
    ) {
        val s = world.entities
        val p = world.player
        val i = p.index
        if (touchesHazardTile(world, i) || s.top(i) > world.grid.height + FALL_OUT_MARGIN) {
            killPlayer(world, events)
            return
        }
        // Decided once per tick so that landing on several enemies at once stomps all of them.
        val descending = s.bottom(i) > p.previousBottom
        for (j in 0 until s.count) {
            if (j == i || !s.alive[j] || s.roles[j] == 0 || !s.overlaps(i, j)) continue
            when {
                s.has(j, Roles.COLLECTIBLE) -> {
                    collect(world, j, events)
                }

                s.has(j, Roles.HAZARD) -> {
                    killPlayer(world, events)
                    return
                }

                s.has(j, Roles.PATROL) -> {
                    if (descending && p.previousBottom <= s.top(j) + STOMP_TOLERANCE) {
                        stomp(world, j, events)
                    } else if (p.graceTicks == 0) {
                        killPlayer(world, events)
                        return
                    }
                }

                s.has(j, Roles.CHECKPOINT) -> {
                    activateCheckpoint(world, j, events)
                }

                s.has(j, Roles.GOAL) -> {
                    world.levelCompleted = true
                    events += GameEvent.LevelCompleted
                    return
                }
            }
        }
    }

    private fun touchesHazardTile(
        world: RuntimeWorld,
        i: Int,
    ): Boolean {
        val s = world.entities
        val columnFrom = floorInt(s.left(i) + HAZARD_MARGIN)
        val columnTo = floorInt(s.right(i) - HAZARD_MARGIN)
        val rowFrom = floorInt(s.top(i) + HAZARD_MARGIN)
        val rowTo = floorInt(s.bottom(i) - HAZARD_MARGIN)
        for (row in rowFrom..rowTo) {
            for (column in columnFrom..columnTo) {
                if (world.grid.behavior(column, row) == TileBehavior.Hazard) return true
            }
        }
        return false
    }

    private fun collect(
        world: RuntimeWorld,
        j: Int,
        events: MutableList<GameEvent>,
    ) {
        val s = world.entities
        s.alive[j] = false
        val variable = s.collectVariable[j]
        world.variables[variable] += s.collectAmount[j]
        events += GameEvent.Collected(s.ids[j], world.project.variables[variable].id, s.collectAmount[j])
    }

    private fun stomp(
        world: RuntimeWorld,
        j: Int,
        events: MutableList<GameEvent>,
    ) {
        val s = world.entities
        val p = world.player
        s.alive[j] = false
        s.vy[p.index] = -p.controller.jumpVelocity * STOMP_BOUNCE
        // A bounce always has the same height, independent of when the jump button was released.
        p.jumpCut = true
        events += GameEvent.EnemyDefeated(s.ids[j])
    }

    private fun activateCheckpoint(
        world: RuntimeWorld,
        j: Int,
        events: MutableList<GameEvent>,
    ) {
        val s = world.entities
        val p = world.player
        if (s.checkpointActivated[j]) return
        s.checkpointActivated[j] = true
        val i = p.index
        // Respawn centered on the checkpoint, standing on the checkpoint's base.
        p.respawnX = s.left(j) + s.bodyWidth[j] / 2.0 - s.bodyWidth[i] / 2.0 - s.bodyOffsetX[i]
        p.respawnY = s.bottom(j) - s.bodyHeight[i] - s.bodyOffsetY[i]
        events += GameEvent.CheckpointReached(s.ids[j])
    }

    private fun killPlayer(
        world: RuntimeWorld,
        events: MutableList<GameEvent>,
    ) {
        val s = world.entities
        val p = world.player
        val i = p.index
        events += GameEvent.PlayerDied
        if (p.deathCounter >= 0) world.variables[p.deathCounter] += 1.0
        s.x[i] = p.respawnX
        s.y[i] = p.respawnY
        s.vx[i] = 0.0
        s.vy[i] = 0.0
        s.grounded[i] = false
        p.jumpBuffer = 0
        p.jumpedSinceGround = false
        p.jumpCut = false
        p.ticksSinceGrounded = p.controller.coyoteTicks + 1
        p.graceTicks = RESPAWN_GRACE_TICKS
    }
}
