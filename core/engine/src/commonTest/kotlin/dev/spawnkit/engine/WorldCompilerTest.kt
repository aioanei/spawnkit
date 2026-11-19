package dev.spawnkit.engine

import dev.spawnkit.model.Body
import dev.spawnkit.model.Collectible
import dev.spawnkit.model.DecodeResult
import dev.spawnkit.model.EntityId
import dev.spawnkit.model.EntityInstance
import dev.spawnkit.model.Prefab
import dev.spawnkit.model.PrefabId
import dev.spawnkit.model.SceneId
import dev.spawnkit.model.Size2
import dev.spawnkit.model.SpawnJson
import dev.spawnkit.model.TileBehavior
import dev.spawnkit.model.Vec2
import dev.spawnkit.templates.EmbeddedTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldCompilerTest {
    @Test
    fun compilesPlatformerTemplateScene() {
        val project = assertIs<DecodeResult.Ok>(SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))).project
        val world = assertIs<CompileResult.Ok>(WorldCompiler.compile(project, SceneId("meadow"))).world
        assertEquals(
            project.scenes
                .first()
                .entities.size,
            world.entityCount,
        )
        assertEquals(TileBehavior.Solid, world.tileBehavior(0, 12))
        assertEquals(TileBehavior.Hazard, world.tileBehavior(36, 11))
        assertEquals(TileBehavior.OneWay, world.tileBehavior(6, 10))
        assertEquals(TileBehavior.Empty, world.tileBehavior(12, 12))
        assertEquals(EntityId("meadow-hero"), world.entityId(world.playerIndex))
    }

    @Test
    fun outsideTheSceneSidesAreWallsAndAboveBelowIsEmpty() {
        val world = assertIs<CompileResult.Ok>(WorldCompiler.compile(testProject(FLAT_GROUND, Vec2(2.0, 3.1)), SceneId("s"))).world
        assertEquals(TileBehavior.Solid, world.tileBehavior(-1, 0))
        assertEquals(TileBehavior.Solid, world.tileBehavior(40, 2))
        assertEquals(TileBehavior.Empty, world.tileBehavior(3, -1))
        assertEquals(TileBehavior.Empty, world.tileBehavior(3, 6))
    }

    @Test
    fun instanceComponentOverridesPrefab() {
        val coinPrefab = Prefab(PrefabId("coin"), "Coin", components = listOf(Body(Size2(0.6, 0.6)), Collectible(COINS, 1.0)))
        val bigCoin =
            EntityInstance(
                id = EntityId("big"),
                name = "Big coin",
                position = Vec2(2.1, 3.2),
                prefab = PrefabId("coin"),
                components = listOf(Collectible(COINS, 5.0)),
            )
        val session = GameSession(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(bigCoin), listOf(coinPrefab)))
        session.step(InputFrame.NONE)
        assertEquals(5.0, session.world.variable(COINS))
    }

    @Test
    fun missingPlayerIsCompileError() {
        val project = testProject(FLAT_GROUND, Vec2(2.0, 3.1))
        val withoutPlayer = project.copy(scenes = project.scenes.map { it.copy(entities = emptyList()) })
        val failed = assertIs<CompileResult.Failed>(WorldCompiler.compile(withoutPlayer, SceneId("s")))
        assertTrue(failed.errors.any { "PlayerController" in it }, failed.errors.toString())
    }

    @Test
    fun unknownTileCharacterIsCompileError() {
        val rows = FLAT_GROUND.toMutableList().also { it[0] = "X" + it[0].drop(1) }
        val failed = assertIs<CompileResult.Failed>(WorldCompiler.compile(testProject(rows, Vec2(2.0, 3.1)), SceneId("s")))
        assertTrue(failed.errors.any { "'X'" in it }, failed.errors.toString())
    }

    @Test
    fun unknownSceneAndPrefabAreCompileErrors() {
        assertIs<CompileResult.Failed>(WorldCompiler.compile(testProject(FLAT_GROUND, Vec2(2.0, 3.1)), SceneId("nope")))
        val orphan = EntityInstance(EntityId("o"), "Orphan", Vec2(1.0, 1.0), prefab = PrefabId("missing"))
        val failed =
            assertIs<CompileResult.Failed>(WorldCompiler.compile(testProject(FLAT_GROUND, Vec2(2.0, 3.1), listOf(orphan)), SceneId("s")))
        assertTrue(failed.errors.any { "missing" in it }, failed.errors.toString())
    }
}
