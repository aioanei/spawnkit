package dev.spawnkit.engine

import dev.spawnkit.model.PlayerController
import dev.spawnkit.model.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformerPhysicsTest {
    @Test
    fun playerFallsAndLandsOnGround() {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 0.0)))
        session.run(120)
        val world = session.world
        assertTrue(world.isGrounded(world.playerIndex))
        assertEquals(FLAT_GROUND_TOP, world.playerBottom, 1e-9)
    }

    @Test
    fun holdingRightMovesEightTilesPerSecond() {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1)))
        session.run(5)
        val startX = session.world.x(session.world.playerIndex)
        session.run(60, right())
        assertEquals(8.0, session.world.x(session.world.playerIndex) - startX, 1e-9)
    }

    @Test
    fun cannotLeaveTheSceneSideways() {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1)))
        session.run(120, InputFrame.of(InputAction.Left))
        assertEquals(0.0, session.world.x(session.world.playerIndex), 1e-9)
    }

    @Test
    fun jumpReachesExpectedApex() {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1)))
        session.run(5)
        val startY = session.world.y(session.world.playerIndex)
        var minY = startY
        repeat(40) {
            session.step(jump())
            minY = minOf(minY, session.world.y(session.world.playerIndex))
        }
        val apex = startY - minY
        assertTrue(apex in 2.69..3.09, "apex was $apex")
    }

    @Test
    fun releasingJumpEarlyGivesALowerJump() {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1)))
        session.run(5)
        val startY = session.world.y(session.world.playerIndex)
        session.step(jump())
        var minY = startY
        repeat(40) {
            session.step(InputFrame.NONE)
            minY = minOf(minY, session.world.y(session.world.playerIndex))
        }
        assertTrue(startY - minY < 2.0, "short hop was ${startY - minY}")
    }

    @Test
    fun coyoteJumpAllowedForSixTicksAfterTheFirstAirborneTick() {
        // The press happens on airborne tick (extra + 2): ticks 2..7 are allowed with coyoteTicks = 6.
        assertTrue(jumpsAfterLeavingLedge(ticksAfterLeaving = 5))
        assertFalse(jumpsAfterLeavingLedge(ticksAfterLeaving = 6))
    }

    @Test
    fun jumpBufferCoversExactlyJumpBufferTicks() {
        // The first tick on which a jump is possible is the tick after landing (grounded is set by the landing move).
        val probe = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 0.0)))
        var landingTick = 0
        while (!probe.world.isGrounded(probe.world.playerIndex)) {
            probe.step(InputFrame.NONE)
            landingTick++
        }
        val firstJumpableTick = landingTick + 1
        assertTrue(jumpsWhenPressedAt(firstJumpableTick - 6))
        assertFalse(jumpsWhenPressedAt(firstJumpableTick - 7))
    }

    @Test
    fun zeroJumpBufferStillJumpsOnPress() {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), controller = PlayerController(jumpBufferTicks = 0)))
        session.run(5)
        session.step(jump())
        assertTrue(session.world.vy(session.world.playerIndex) < 0.0)
    }

    @Test
    fun landsOnOneWayPlatformWhileMovingSideways() {
        val rows = listOf("..........", "..........", "..........", "..========", "..........", "##########")
        val session = GameSession(testProject(rows, Vec2(1.5, 0.0)))
        session.run(90, right())
        assertEquals(3.0, session.world.playerBottom, 1e-9)
    }

    @Test
    fun oneWayPlatformSupportsFromAboveOnly() {
        val rows =
            listOf(
                "..........",
                "..........",
                "..........",
                "====......",
                "..........",
                "##########",
            )
        val fromAbove = GameSession(testProject(rows, Vec2(1.0, 0.0)))
        fromAbove.run(60)
        assertEquals(3.0, fromAbove.world.playerBottom, 1e-9)

        val fromBelow = GameSession(testProject(rows, Vec2(1.0, 4.1)))
        fromBelow.run(5)
        assertEquals(5.0, fromBelow.world.playerBottom, 1e-9)
        fromBelow.run(30, jump())
        fromBelow.run(60)
        assertEquals(3.0, fromBelow.world.playerBottom, 1e-9, "jumped up through the platform and landed on it")
    }

    @Test
    fun solidCeilingStopsAJump() {
        val rows = listOf("..........", "##########", "..........", "..........", "##########")
        val session = GameSession(testProject(rows, Vec2(1.0, 3.1)))
        session.run(5)
        session.run(30, jump())
        assertTrue(session.world.y(session.world.playerIndex) >= 2.0 - 1e-9, "head must stay below the ceiling")
    }

    @Test
    fun longFallDoesNotTunnelThroughFloor() {
        val rows = List(45) { ".........." } + listOf("##########") + List(4) { ".........." }
        val session = GameSession(testProject(rows, Vec2(1.0, 0.0)))
        session.run(300)
        assertTrue(session.world.isGrounded(session.world.playerIndex))
        assertEquals(45.0, session.world.playerBottom, 1e-9)
    }

    @Test
    fun fallingOutOfSceneRespawns() {
        val rows = List(6) { ".........." }
        val session = GameSession(testProject(rows, Vec2(2.0, 0.0)))
        var events = emptyList<GameEvent>()
        var ticks = 0
        while (GameEvent.PlayerDied !in events && ticks < 600) {
            events = session.step(InputFrame.NONE)
            ticks++
        }
        assertTrue(GameEvent.PlayerDied in events, "player should die after falling out")
        val world = session.world
        assertEquals(2.0, world.x(world.playerIndex), 1e-9)
        assertEquals(0.0, world.y(world.playerIndex), 1e-9)
        assertEquals(1.0, world.variable(DEATHS))
    }

    private fun jumpsWhenPressedAt(pressTick: Int): Boolean {
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 0.0)))
        var tick = 0
        while (tick < pressTick - 1) {
            session.step(InputFrame.NONE)
            tick++
        }
        var jumped = false
        repeat(20) {
            session.step(jump())
            if (session.world.vy(session.world.playerIndex) < 0.0) jumped = true
        }
        return jumped
    }

    private fun jumpsAfterLeavingLedge(ticksAfterLeaving: Int): Boolean {
        val rows = List(4) { "...................." } + List(2) { "#####..............." }
        val session = GameSession(testProject(rows, Vec2(3.0, 3.1)))
        session.run(3)
        var guard = 0
        while (session.world.isGrounded(session.world.playerIndex) && guard++ < 100) session.step(right())
        session.run(ticksAfterLeaving, right())
        session.step(InputFrame.of(InputAction.Right, InputAction.Jump))
        return session.world.vy(session.world.playerIndex) < 0.0
    }
}
