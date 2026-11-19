package dev.spawnkit.templates

import dev.spawnkit.model.Component
import dev.spawnkit.model.DecodeResult
import dev.spawnkit.model.EmptyTileChars
import dev.spawnkit.model.EntityInstance
import dev.spawnkit.model.GameProject
import dev.spawnkit.model.Goal
import dev.spawnkit.model.PlayerController
import dev.spawnkit.model.SpawnJson
import dev.spawnkit.model.prefab
import dev.spawnkit.model.validateRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbeddedTemplatesTest {
    private val platformer: GameProject by lazy {
        val result = SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))
        assertIs<DecodeResult.Ok>(result, (result as? DecodeResult.Error)?.message).project
    }

    @Test
    fun platformerTemplateDecodesAndIsValid() {
        assertEquals(3, platformer.scenes.size)
        platformer.scenes.forEach { scene ->
            scene.layers.forEach { layer ->
                assertEquals(emptyList(), layer.validateRows(scene.size.width, scene.size.height), scene.name)
            }
            assertEquals(1, scene.entities.count { it.has<PlayerController>() }, "players in ${scene.name}")
            assertTrue(scene.entities.any { it.has<Goal>() }, "goal in ${scene.name}")
        }
    }

    @Test
    fun startSceneExists() {
        assertTrue(platformer.scenes.any { it.id == platformer.settings.startScene })
    }

    @Test
    fun everyTileCharacterIsInTileset() {
        platformer.scenes.forEach { scene ->
            scene.layers.flatMap { it.rows }.joinToString("").forEach { ch ->
                assertTrue(ch in EmptyTileChars || ch.toString() in platformer.tileset, "'$ch' in ${scene.name}")
            }
        }
    }

    @Test
    fun prefabReferencesResolveAndEntityIdsAreUnique() {
        val entities = platformer.scenes.flatMap { it.entities }
        entities.mapNotNull { it.prefab }.forEach { assertNotNull(platformer.prefab(it), "prefab ${it.value}") }
        assertEquals(entities.size, entities.map { it.id }.toSet().size)
    }

    @Test
    fun eachSceneHasEnoughContent() {
        platformer.scenes.forEach { scene ->
            assertTrue(scene.entities.count { it.prefab?.value == "coin" } >= 8, "coins in ${scene.name}")
            assertTrue(scene.entities.count { it.prefab?.value == "slime" } >= 2, "slimes in ${scene.name}")
        }
    }

    private inline fun <reified T : Component> EntityInstance.has(): Boolean =
        components.any { it is T } ||
            prefab
                ?.let { platformer.prefab(it) }
                ?.components
                .orEmpty()
                .any { it is T }
}
