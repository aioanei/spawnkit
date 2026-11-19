package dev.spawnkit.engine

import kotlin.math.min

/** Applies player input, jumping rules and gravity, then moves the player against the tiles. */
internal object PlatformerSystem {
    fun step(
        world: RuntimeWorld,
        input: InputFrame,
    ) {
        val p = world.player
        val s = world.entities
        val i = p.index
        val c = p.controller

        var direction = 0.0
        if (input.isDown(InputAction.Left)) direction -= 1.0
        if (input.isDown(InputAction.Right)) direction += 1.0
        s.vx[i] = direction * c.moveSpeed

        val jumpDown = input.isDown(InputAction.Jump)
        if (jumpDown && !p.jumpWasDown) {
            p.jumpBuffer = c.jumpBufferTicks
        } else if (p.jumpBuffer > 0) {
            p.jumpBuffer--
        }
        val canJump = !p.jumpedSinceGround && (s.grounded[i] || p.ticksSinceGrounded <= c.coyoteTicks)
        if (p.jumpBuffer > 0 && canJump) {
            s.vy[i] = -c.jumpVelocity
            p.jumpBuffer = 0
            p.jumpedSinceGround = true
            p.jumpCut = false
        }
        if (!jumpDown && p.jumpedSinceGround && !p.jumpCut && s.vy[i] < 0.0) {
            s.vy[i] *= JUMP_CUT
            p.jumpCut = true
        }
        p.jumpWasDown = jumpDown

        s.vy[i] = min(s.vy[i] + world.physics.gravity * DT, world.physics.maxFallSpeed)
        p.previousBottom = s.bottom(i)
        TilePhysics.moveX(world, i, s.vx[i] * DT)
        TilePhysics.moveY(world, i, s.vy[i] * DT)
        if (s.grounded[i]) {
            p.ticksSinceGrounded = 0
            p.jumpedSinceGround = false
        } else if (p.ticksSinceGrounded <= c.coyoteTicks) {
            p.ticksSinceGrounded++
        }
    }
}
