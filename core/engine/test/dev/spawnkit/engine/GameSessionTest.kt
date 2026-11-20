package dev.spawnkit.engine

import dev.spawnkit.model.Body
import dev.spawnkit.model.Collectible
import dev.spawnkit.model.DecodeResult
import dev.spawnkit.model.EntityInstance
import dev.spawnkit.model.GameProject
import dev.spawnkit.model.SceneId
import dev.spawnkit.model.Size2
import dev.spawnkit.model.SpawnJson
import dev.spawnkit.model.Vec2
import dev.spawnkit.templates.EmbeddedTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameSessionTest {
    private fun twoScenes(): GameProject {
        val coin = entity("coin", 2.1, 3.2, Body(Size2(0.6, 0.6)), Collectible(COINS, 1.0))
        val base = testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(coin))
        val first = base.scenes.single()
        val second = first.copy(id = SceneId("s2"), name = "S2", entities = first.entities.map { it.withSceneSuffix() })
        return base.copy(scenes = listOf(first, second))
    }

    private fun EntityInstance.withSceneSuffix(): EntityInstance = copy(id = dev.spawnkit.model.EntityId(id.value + "-2"))

    @Test
    fun variablesCarryOverToTheNextScene() {
        val session = GameSession(twoScenes())
        session.step(InputFrame.NONE)
        assertEquals(1.0, session.world.variable(COINS))
        assertTrue(session.advanceToNextScene())
        assertEquals(1, session.sceneIndex)
        assertEquals(1.0, session.world.variable(COINS))
        session.step(InputFrame.NONE)
        assertEquals(2.0, session.world.variable(COINS))
    }

    @Test
    fun restartRestoresVariablesFromTheStartOfTheLevel() {
        val session = GameSession(twoScenes())
        session.step(InputFrame.NONE)
        session.advanceToNextScene()
        session.step(InputFrame.NONE)
        assertEquals(2.0, session.world.variable(COINS))
        session.restartLevel()
        assertEquals(1.0, session.world.variable(COINS))
        assertEquals(0L, session.world.tick)
    }

    @Test
    fun advancingPastTheLastSceneReturnsFalse() {
        val session = GameSession(twoScenes())
        assertTrue(session.advanceToNextScene())
        assertFalse(session.advanceToNextScene())
        assertEquals(1, session.sceneIndex)
    }

    @Test
    fun idlingAtEveryTemplateCheckpointIsSafe() {
        val project = assertIs<DecodeResult.Ok>(SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))).project
        project.scenes.forEach { scene ->
            val checkpoint = scene.entities.first { it.prefab?.value == "checkpoint" }
            val movedPlayer =
                scene.entities.map { entity ->
                    if (entity.tags.contains(
                            "player",
                        )
                    ) {
                        entity.copy(position = Vec2(checkpoint.position.x, checkpoint.position.y + 1.0 - 0.9))
                    } else {
                        entity
                    }
                }
            val testScene = scene.copy(entities = movedPlayer)
            val session = GameSession(project.copy(scenes = listOf(testScene)), scene.id)
            val deaths = session.run(600).count { it == GameEvent.PlayerDied }
            assertEquals(0, deaths, "player idling at the checkpoint of ${scene.name} died")
        }
    }
}
