package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.RuntimeBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Scenes are added, renamed, reordered and removed by operations; the first scene is where the game starts (C9). */
class SceneOperationsTest {
    private val runtime = RuntimeBuilder().also { Graphics2D(it) }.build()
    private val editor = Editor(runtime)

    private fun names() = editor.project.scenes.map { it.name }

    private fun added(name: String): String =
        assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddScene(name))).createdIds.single()

    @Test
    fun scenesAreAddedRenamedMovedAndRemovedWithUndo() {
        val level = editor.project.scenes
            .first()
            .id
        val title = added("Title")
        assertEquals(listOf("Main scene", "Title"), names())

        editor.apply(EditOperation.RenameScene(level, "Level 1"))
        assertIs<OperationResult.Applied>(editor.apply(EditOperation.MoveScene(title, 0)))
        assertEquals(listOf("Title", "Level 1"), names(), "The first scene is the start scene")

        assertIs<OperationResult.Applied>(editor.apply(EditOperation.RemoveScene(level)))
        assertEquals(listOf("Title"), names())
        editor.undo()
        assertEquals(listOf("Title", "Level 1"), names())
    }

    @Test
    fun theLastSceneCannotBeRemovedAndMovesStayInRange() {
        val only = editor.project.scenes
            .single()
            .id

        val removed = editor.apply(EditOperation.RemoveScene(only))
        val moved = editor.apply(EditOperation.MoveScene(only, 1))

        assertEquals("A game needs at least one scene", assertIs<OperationResult.Rejected>(removed).message)
        assertIs<OperationResult.Rejected>(moved)
        assertEquals(listOf("Main scene"), names())
    }

    @Test
    fun catalogBuildsSceneOperationsFromJson() {
        val catalog = EditingOperations.catalog
        fun json(
            id: String,
            input: String,
        ) = editor.apply(catalog.buildFromJson(id, Json.parseToJsonElement(input).jsonObject, editor.catalogContext()))

        assertIs<OperationResult.Applied>(json("add_scene", """{"name":"Title"}"""))
        val title = editor.project.scenes
            .last()
            .id
        assertIs<OperationResult.Applied>(json("rename_scene", """{"scene":"$title","name":"Start"}"""))
        assertIs<OperationResult.Applied>(json("move_scene", """{"scene":"$title","to":1}"""))
        assertEquals("Make Start the start scene", editor.undoLabel)
        assertEquals(listOf("Start", "Main scene"), names())
        assertIs<OperationResult.Applied>(json("remove_scene", """{"scene":"$title"}"""))
        assertEquals(listOf("Main scene"), names())
        assertIs<BuildResult.Rejected>(catalog.buildFromJson("move_scene", Json.parseToJsonElement("""{"scene":"nope","to":1}""").jsonObject, editor.catalogContext()))
    }
}
