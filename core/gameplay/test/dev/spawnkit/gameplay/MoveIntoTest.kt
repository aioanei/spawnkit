package dev.spawnkit.gameplay

import dev.spawnkit.editing.BuildResult
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Grouping objects (C15): "Move into…" keeps an object where it is on screen. */
class MoveIntoTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private fun add(
        name: String,
        x: Double,
        y: Double,
        scale: Double = 1.0,
        parent: String? = null,
    ): String =
        assertIs<OperationResult.Applied>(
            editor.apply(
                EditOperation.AddObject(
                    scene,
                    name,
                    parent,
                    listOf(ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y, "scaleX" to scale, "scaleY" to scale))),
                ),
            ),
        ).createdIds.single()

    private fun transform(id: String): Map<String, Any?> =
        editor.project
            .scene(scene)!!
            .find(id)!!
            .component("graphics:transform")!!
            .values

    private fun move(
        id: String,
        parent: String?,
    ): BuildResult =
        engine.operations.build(
            "move_into",
            listOfNotNull("scene" to scene, "object" to id, parent?.let { "parent" to it }).toMap(),
            editor.catalogContext(),
        )

    private fun apply(result: BuildResult) = assertIs<OperationResult.Applied>(editor.apply(assertIs<BuildResult.Ready>(result).operation))

    @Test
    fun anObjectMovedIntoAGroupStaysWhereItIsOnScreen() {
        val panel = add("Panel", 2.0, 1.0, scale = 2.0)
        val label = add("Label", 4.0, 3.0)
        apply(move(label, panel))

        assertEquals(panel, editor.project.scene(scene)!!.find(label)!!.parent)
        assertEquals(1.0, transform(label)["x"])
        assertEquals(1.0, transform(label)["y"])
        assertEquals(0.5, transform(label)["scaleX"], "half size inside a double-size group, so it looks the same")
        assertEquals("Move Label into Panel", editor.undoLabel)
    }

    @Test
    fun anObjectMovedOutOfAGroupStaysWhereItIsOnScreen() {
        val panel = add("Panel", 2.0, 1.0, scale = 2.0)
        val label = add("Label", 1.0, 1.0, parent = panel)
        apply(move(label, null))

        assertNull(editor.project.scene(scene)!!.find(label)!!.parent)
        assertEquals(4.0, transform(label)["x"])
        assertEquals(3.0, transform(label)["y"])
        assertEquals(2.0, transform(label)["scaleX"])
        assertEquals("Move Label to the top level", editor.undoLabel)
    }

    @Test
    fun oneUndoPutsItBack() {
        val panel = add("Panel", 2.0, 1.0, scale = 2.0)
        add("Label", 4.0, 3.0)
        val before = editor.project
        apply(move(editor.project.scene(scene)!!.objects.last().id, panel))
        editor.undo()

        assertEquals(before, editor.project)
    }

    @Test
    fun impossibleMovesAreExplained() {
        val panel = add("Panel", 0.0, 0.0)
        val label = add("Label", 0.0, 0.0, parent = panel)

        assertEquals(BuildResult.Rejected("Panel cannot go inside itself or something inside it"), move(panel, label))
        assertEquals(BuildResult.Rejected("Panel cannot go inside itself or something inside it"), move(panel, panel))
        assertEquals(BuildResult.Rejected("Label is already inside Panel"), move(label, panel))
        assertEquals(BuildResult.Rejected("Panel is already at the top level"), move(panel, null))
    }
}
