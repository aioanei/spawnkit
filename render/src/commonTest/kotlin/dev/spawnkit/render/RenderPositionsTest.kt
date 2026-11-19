package dev.spawnkit.render

import dev.spawnkit.engine.GameSession
import dev.spawnkit.engine.InputAction
import dev.spawnkit.engine.InputFrame
import dev.spawnkit.model.DecodeResult
import dev.spawnkit.model.SpawnJson
import dev.spawnkit.templates.EmbeddedTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RenderPositionsTest {
    @Test
    fun interpolatesWithoutChangingSimulationState() {
        val session = session()
        val positions = RenderPositions()
        val world = session.world
        val player = world.playerIndex
        val startX = world.x(player)
        val startY = world.y(player)
        positions.capture(world)
        session.step(InputFrame.of(InputAction.Right))
        val hash = session.stateHash()
        assertEquals(startX, positions.x(world, player, 0.0))
        assertEquals((startX + world.x(player)) / 2, positions.x(world, player, 0.5), 1e-9)
        assertEquals((startY + world.y(player)) / 2, positions.y(world, player, 0.5), 1e-9)
        assertEquals(world.x(player), positions.x(world, player, 1.0))
        assertEquals(hash, session.stateHash())
    }

    @Test
    fun restartingOrChangingScenesNeverInterpolatesFromOldWorld() {
        val session = session()
        val positions = RenderPositions()
        positions.capture(session.world)
        repeat(10) { session.step(InputFrame.of(InputAction.Right)) }
        session.restartLevel()
        assertEquals(session.world.x(session.world.playerIndex), positions.x(session.world, session.world.playerIndex, 0.0))
        session.advanceToNextScene()
        assertEquals(session.world.y(session.world.playerIndex), positions.y(session.world, session.world.playerIndex, 0.0))
    }

    @Test
    fun capturingAfterTeleportSnapsToCurrentPosition() {
        val session = session()
        val positions = RenderPositions()
        positions.capture(session.world)
        session.step(InputFrame.of(InputAction.Right))
        positions.capture(session.world)
        assertEquals(session.world.x(session.world.playerIndex), positions.x(session.world, session.world.playerIndex, 0.0))
    }

    private fun session(): GameSession =
        GameSession(assertIs<DecodeResult.Ok>(SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))).project)
}
