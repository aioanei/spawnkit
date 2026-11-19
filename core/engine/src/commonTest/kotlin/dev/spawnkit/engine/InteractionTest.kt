package dev.spawnkit.engine

import dev.spawnkit.model.Body
import dev.spawnkit.model.Checkpoint
import dev.spawnkit.model.Collectible
import dev.spawnkit.model.EntityId
import dev.spawnkit.model.Goal
import dev.spawnkit.model.Hazard
import dev.spawnkit.model.Patrol
import dev.spawnkit.model.Size2
import dev.spawnkit.model.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InteractionTest {
    @Test
    fun collectingCoinIncrementsVariableOnce() {
        val coin = entity("coin", 4.0, 3.2, Body(Size2(0.6, 0.6)), Collectible(COINS, 1.0))
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(coin)))
        val events = session.run(60, right())
        assertEquals(1.0, session.world.variable(COINS))
        assertEquals(1, events.count { it is GameEvent.Collected })
        assertFalse(session.world.isAlive(session.world.indexOf(EntityId("coin"))))
    }

    @Test
    fun hazardTileKillsPlayer() {
        val rows = FLAT_GROUND.toMutableList().also { it[3] = ".....^" + ".".repeat(34) }
        val session = GameSession(testProject(rows, Vec2(2.0, 3.1)))
        val events = session.run(60, right())
        val deaths = events.count { it == GameEvent.PlayerDied }
        assertTrue(deaths >= 1)
        assertEquals(deaths.toDouble(), session.world.variable(DEATHS), "death counter matches PlayerDied events")
    }

    @Test
    fun hazardEntityKillsPlayer() {
        val spikes = entity("spikes", 5.0, 3.5, Body(Size2(1.0, 0.5)), Hazard)
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(spikes)))
        assertTrue(GameEvent.PlayerDied in session.run(60, right()))
    }

    @Test
    fun standingNextToSpikesIsSafe() {
        val rows = FLAT_GROUND.toMutableList().also { it[3] = "...^" + ".".repeat(36) }
        val session = GameSession(testProject(rows, Vec2(2.15, 3.1)))
        assertFalse(GameEvent.PlayerDied in session.run(60))
    }

    @Test
    fun checkpointChangesRespawnPoint() {
        val rows = List(4) { ".".repeat(20) } + List(2) { "##########.........." }
        val flag = entity("flag", 5.0, 3.0, Body(Size2(0.4, 1.0)), Checkpoint)
        val session = GameSession(testProject(rows, Vec2(1.0, 3.1), listOf(flag)))
        val events = mutableListOf<GameEvent>()
        var guard = 0
        while (GameEvent.PlayerDied !in events && guard++ < 600) events += session.step(right())
        assertTrue(events.any { it is GameEvent.CheckpointReached })
        assertTrue(GameEvent.PlayerDied in events)
        val world = session.world
        // Centered on the 0.4-wide flag at x = 5.0: 5.2 - 0.8 / 2.
        assertEquals(4.8, world.x(world.playerIndex), 1e-9)
        assertEquals(4.0, world.playerBottom, 1e-9)
    }

    @Test
    fun landingOnTwoEnemiesAtOnceStompsBoth() {
        val left = entity("a", 2.0, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 0.0))
        val right = entity("b", 2.9, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 0.0))
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.5, 0.0), listOf(left, right)))
        val events = mutableListOf<GameEvent>()
        var guard = 0
        while (events.none { it is GameEvent.EnemyDefeated } && guard++ < 120) events += session.step(InputFrame.NONE)
        assertEquals(2, events.count { it is GameEvent.EnemyDefeated }, events.toString())
        assertFalse(GameEvent.PlayerDied in events)
    }

    @Test
    fun stompBounceHeightDoesNotDependOnJumpRelease() {
        fun bounceApex(releaseJumpBeforeLanding: Boolean): Double {
            val slime = entity("slime", 2.0, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 0.0))
            val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1 - 4.0), listOf(slime)))
            session.step(InputFrame.NONE)
            var bounced = false
            var minY = Double.MAX_VALUE
            repeat(120) {
                val input = if (releaseJumpBeforeLanding) InputFrame.NONE else jump()
                if (session.step(input).any { it is GameEvent.EnemyDefeated }) bounced = true
                if (bounced) minY = minOf(minY, session.world.y(session.world.playerIndex))
            }
            assertTrue(bounced)
            return minY
        }
        assertEquals(bounceApex(releaseJumpBeforeLanding = true), bounceApex(releaseJumpBeforeLanding = false), 1e-9)
    }

    @Test
    fun respawnGivesAGracePeriodAgainstEnemies() {
        // The checkpoint sits in a slime's patrol path; after respawning there the player must not die immediately.
        val rows = List(4) { ".".repeat(20) } + List(2) { "##########.........." }
        val flag = entity("flag", 4.0, 3.0, Body(Size2(0.4, 1.0)), Checkpoint)
        val slime = entity("slime", 6.0, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 2.5))
        val session = GameSession(testProject(rows, Vec2(3.0, 3.1), listOf(flag, slime)))
        session.run(3)
        // Fall into the pit on the right by walking past the slime while it is immune-free: wait for the first death.
        val events = mutableListOf<GameEvent>()
        var guard = 0
        while (GameEvent.PlayerDied !in events && guard++ < 600) events += session.step(InputFrame.NONE)
        assertTrue(GameEvent.PlayerDied in events, "the slime kills the idle player once")
        assertTrue(session.world.playerInGracePeriod)
        val afterRespawn = session.run(59)
        assertFalse(GameEvent.PlayerDied in afterRespawn, "no death during the grace period")
    }

    @Test
    fun stompDefeatsPatrol() {
        val slime = entity("slime", 2.0, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 0.0))
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 0.0), listOf(slime)))
        val events = mutableListOf<GameEvent>()
        var guard = 0
        while (events.none { it is GameEvent.EnemyDefeated } && guard++ < 120) events += session.step(InputFrame.NONE)
        assertTrue(events.any { it is GameEvent.EnemyDefeated }, events.toString())
        assertFalse(GameEvent.PlayerDied in events)
        assertTrue(session.world.vy(session.world.playerIndex) < 0.0, "player bounces")
    }

    @Test
    fun sideContactWithPatrolKills() {
        val slime = entity("slime", 6.0, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 2.5))
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(slime)))
        session.run(5)
        val events = session.run(120, InputFrame.of(InputAction.Right))
        assertTrue(GameEvent.PlayerDied in events)
    }

    @Test
    fun patrolTurnsAtLedges() {
        val rows = List(4) { ".".repeat(20) } + List(2) { "......#####........." }
        val slime = entity("slime", 8.0, 3.3, Body(Size2(0.9, 0.7)), Patrol(speed = 2.5, turnAtEdges = true))
        val session = GameSession(testProject(rows, Vec2(0.5, 0.0), listOf(slime)))
        val index = session.world.indexOf(EntityId("slime"))
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        repeat(600) {
            session.step(InputFrame.NONE)
            minX = minOf(minX, session.world.x(index))
            maxX = maxOf(maxX, session.world.x(index))
        }
        assertTrue(minX >= 6.0 - 0.06 && maxX + 0.9 <= 11.0 + 0.06, "slime stayed on its platform: $minX..$maxX")
        assertTrue(maxX - minX > 3.0, "slime actually patrolled")
    }

    @Test
    fun goalCompletesLevelAndFreezesSimulation() {
        val goal = entity("goal", 6.0, 2.0, Body(Size2(1.0, 2.0)), Goal)
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(goal)))
        val events = session.run(60, right())
        assertEquals(1, events.count { it == GameEvent.LevelCompleted })
        assertTrue(session.world.levelCompleted)
        val tick = session.world.tick
        assertEquals(emptyList(), session.step(right()))
        assertEquals(tick, session.world.tick)
    }

    @Test
    fun stateHashIsStableForSameInputs() {
        assertEquals(scriptedHash(seedOffset = 0), scriptedHash(seedOffset = 0))
    }

    @Test
    fun stateHashDiffersForDifferentInputs() {
        assertNotEquals(scriptedHash(seedOffset = 0), scriptedHash(seedOffset = 1))
    }

    private fun scriptedHash(seedOffset: Int): Long {
        val coin = entity("coin", 9.0, 3.2, Body(Size2(0.6, 0.6)), Collectible(COINS, 1.0))
        val slime = entity("slime", 20.0, 3.3, Body(Size2(0.9, 0.7)), Patrol())
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(coin, slime)))
        repeat(600) { tick ->
            val input =
                when ((tick + seedOffset) % 90) {
                    in 0..40 -> right()
                    in 41..50 -> InputFrame.of(InputAction.Right, InputAction.Jump)
                    else -> InputFrame.NONE
                }
            session.step(input)
        }
        return session.stateHash()
    }
}
