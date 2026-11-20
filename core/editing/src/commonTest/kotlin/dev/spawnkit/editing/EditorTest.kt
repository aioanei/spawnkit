package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.graphics.Shape2D
import dev.spawnkit.graphics.Transform2D
import dev.spawnkit.graphics.renderList
import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorTest {
    private class Fixture {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val runtime = builder.build()
        val editor = Editor(runtime)
        val scene: String get() =
            editor.project.scenes
                .first()
                .id

        fun add(
            name: String,
            x: Double = 0.0,
            parent: String? = null,
        ): String {
            val result =
                editor.apply(
                    EditOperation.AddObject(
                        scene,
                        name,
                        parent,
                        listOf(ComponentDoc("graphics:transform", mapOf("x" to x)), ComponentDoc("graphics:shape")),
                    ),
                )
            return assertIs<OperationResult.Applied>(result).createdIds.single()
        }
    }

    @Test
    fun operationsEditTheProjectAndUndoRedoRestoreExactStates() {
        val fixture = Fixture()
        val editor = fixture.editor
        val empty = editor.project
        val hero = fixture.add("Hero", x = 2.0)
        val afterAdd = editor.project
        assertIs<OperationResult.Applied>(
            editor.apply(EditOperation.SetProperty(fixture.scene, hero, "graphics:shape", "color", 0xFF2196F3)),
        )
        assertIs<OperationResult.Applied>(editor.apply(EditOperation.RenameObject(fixture.scene, hero, "Player")))
        assertEquals(
            "Player",
            editor.project
                .scene(fixture.scene)
                ?.find(hero)
                ?.name,
        )
        assertEquals("Rename to Player", editor.undoLabel)
        assertTrue(editor.undo())
        assertTrue(editor.undo())
        assertEquals(afterAdd, editor.project)
        assertTrue(editor.undo())
        assertEquals(empty, editor.project)
        assertFalse(editor.undo())
        assertTrue(editor.redo())
        assertEquals(afterAdd, editor.project)
        fixture.add("Other")
        assertFalse(editor.redo(), "A new edit clears redo")
    }

    @Test
    fun invalidOperationsAreRejectedWithoutChanges() {
        val fixture = Fixture()
        val editor = fixture.editor
        val hero = fixture.add("Hero")
        val child = fixture.add("Hat", parent = hero)
        val before = editor.project
        val revision = editor.revision
        val rejected =
            listOf(
                EditOperation.SetProperty(fixture.scene, hero, "graphics:shape", "color", 0x1FFFFFFFFL),
                EditOperation.SetProperty(fixture.scene, hero, "graphics:shape", "form", "triangle"),
                EditOperation.SetProperty(fixture.scene, hero, "graphics:shape", "nope", 1L),
                EditOperation.SetProperty(fixture.scene, hero, "graphics:camera", "zoom", 2.0),
                EditOperation.AddComponent(fixture.scene, hero, "graphics:shape"),
                EditOperation.AddComponent(fixture.scene, hero, "unknown:thing"),
                EditOperation.ReparentObject(fixture.scene, hero, child),
                EditOperation.RenameObject(fixture.scene, hero, "  "),
                EditOperation.RemoveObject(fixture.scene, "obj-999"),
                EditOperation.AddObject("scene-999", "Ghost"),
                EditOperation.AddObject(
                    fixture.scene,
                    "Twins",
                    components = listOf(ComponentDoc("graphics:shape"), ComponentDoc("graphics:shape")),
                ),
            )
        for (operation in rejected) assertIs<OperationResult.Rejected>(editor.apply(operation), operation.toString())
        assertEquals(before, editor.project)
        assertEquals(revision, editor.revision)
        assertTrue(editor.validate("graphics:shape", "color", 0x1FFFFFFFFL)?.startsWith("Color") == true)
        assertNull(editor.validate("graphics:transform", "x", 1.0))
    }

    @Test
    fun batchesAreAtomicAndUndoAsOneStep() {
        val fixture = Fixture()
        val editor = fixture.editor
        val hero = fixture.add("Hero")
        val before = editor.project
        val bad =
            EditOperation.Batch(
                listOf(
                    EditOperation.SetProperty(fixture.scene, hero, "graphics:transform", "x", 5.0),
                    EditOperation.SetProperty(fixture.scene, hero, "graphics:transform", "y", Double.NaN),
                ),
                "Move Hero",
            )
        assertIs<OperationResult.Rejected>(editor.apply(bad))
        assertEquals(before, editor.project)
        val move =
            EditOperation.Batch(
                listOf(
                    EditOperation.SetProperty(fixture.scene, hero, "graphics:transform", "x", 5.0),
                    EditOperation.SetProperty(fixture.scene, hero, "graphics:transform", "y", 3),
                ),
                "Move Hero",
            )
        assertIs<OperationResult.Applied>(editor.apply(move))
        assertEquals(
            mapOf("x" to 5.0, "y" to 3.0),
            editor.project
                .scene(fixture.scene)
                ?.find(hero)
                ?.component("graphics:transform")
                ?.values,
        )
        assertEquals("Move Hero", editor.undoLabel)
        editor.undo()
        assertEquals(before, editor.project)
    }

    @Test
    fun duplicateCopiesSubtreesWithFreshIdsAndRemoveDeletesSubtrees() {
        val fixture = Fixture()
        val editor = fixture.editor
        val hero = fixture.add("Hero", x = 1.0)
        val hat = fixture.add("Hat", parent = hero)
        val result = assertIs<OperationResult.Applied>(editor.apply(EditOperation.DuplicateObject(fixture.scene, hero)))
        val (copy, copiedHat) = result.createdIds
        val scene = checkNotNull(editor.project.scene(fixture.scene))
        assertEquals("Hero copy", scene.find(copy)?.name)
        assertEquals(copy, scene.find(copiedHat)?.parent)
        assertEquals(listOf(hero, hat, copy, copiedHat), scene.objects.map { it.id })
        assertEquals(
            scene.objects.size,
            scene.objects
                .map { it.id }
                .toSet()
                .size,
        )
        editor.apply(EditOperation.RemoveObject(fixture.scene, hero))
        assertEquals(
            listOf(copy, copiedHat),
            editor.project
                .scene(fixture.scene)
                ?.objects
                ?.map { it.id },
        )
    }

    @Test
    fun builtWorldsMirrorTheSceneAndMapEntitiesBackToObjects() {
        val fixture = Fixture()
        val editor = fixture.editor
        val hero = fixture.add("Hero", x = 2.0)
        val hat = fixture.add("Hat", x = 0.5, parent = hero)
        editor.apply(EditOperation.SetProperty(fixture.scene, hero, "graphics:shape", "width", 2.0))
        val built = editor.buildWorld()
        val heroEntity = checkNotNull(built.entity(hero))
        assertEquals(Transform2D(x = 2.0), built.world.get(heroEntity, fixture.graphics.transform))
        assertEquals(Shape2D(width = 2.0), built.world.get(heroEntity, fixture.graphics.shape))
        assertEquals(listOf(built.entity(hat)), built.world.children(heroEntity))
        // Editor selection: hit test in the built world, then map back to the authored object.
        val frame = fixture.graphics.renderList(built.world)
        assertEquals(hat, frame.hitTest(2.5, 0.0)?.let(built::objectId))
        assertEquals(hero, frame.hitTest(1.2, 0.0)?.let(built::objectId))
        assertNull(frame.hitTest(10.0, 10.0))
    }

    @Test
    fun playWorldsAreIsolatedFromLaterEdits() {
        val fixture = Fixture()
        val editor = fixture.editor
        val hero = fixture.add("Hero", x = 1.0)
        val play = editor.buildWorld()
        editor.apply(EditOperation.SetProperty(fixture.scene, hero, "graphics:transform", "x", 9.0))
        editor.apply(EditOperation.RemoveObject(fixture.scene, hero))
        val playHero = checkNotNull(play.entity(hero))
        assertEquals(1.0, play.world.get(playHero, fixture.graphics.transform)?.x)
        play.world.step()
        assertTrue(play.world.isAlive(playHero))
        assertTrue(editor.buildWorld().world.entityCount == 0)
    }

    @Test
    fun scenesCanBeAddedAndListenersSeeEveryChange() {
        val fixture = Fixture()
        val editor = fixture.editor
        val seen = mutableListOf<Long>()
        val stop = editor.onChange { seen += editor.revision }
        val created = assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddScene("Level 2"))).createdIds.single()
        editor.apply(EditOperation.RenameScene(created, "Boss level"))
        editor.undo()
        stop()
        editor.redo()
        assertEquals(listOf(1L, 2L, 3L), seen)
        assertEquals(listOf("Main scene", "Boss level"), editor.project.scenes.map { it.name })
        assertTrue(
            editor.project.scenes
                .map { it.id }
                .toSet()
                .size == 2,
        )
    }
}
