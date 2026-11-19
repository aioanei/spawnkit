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
        if (p.graceTicks > 0) p.graceTicks--

        var direction = 0.0
        if (input.isDown(InputAction.Left)) direction -= 1.0
        if (input.isDown(InputAction.Right)) direction += 1.0
        s.vx[i] = direction * c.moveSpeed

        // A press jumps on this tick or on any of the next `jumpBufferTicks` ticks on which jumping is possible.
        val jumpDown = input.isDown(InputAction.Jump)
        val pressed = jumpDown && !p.jumpWasDown
        if (pressed) p.jumpBuffer = c.jumpBufferTicks
        val wantsJump = pressed || p.jumpBuffer > 0
        // Coyote time: a jump is still allowed for `coyoteTicks` ticks after the first airborne tick.
        val canJump = !p.jumpedSinceGround && (s.grounded[i] || p.ticksSinceGrounded <= c.coyoteTicks)
        if (wantsJump && canJump) {
            s.vy[i] = -c.jumpVelocity
            p.jumpBuffer = 0
            p.jumpedSinceGround = true
            p.jumpCut = false
        } else if (!pressed && p.jumpBuffer > 0) {
            p.jumpBuffer--
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
