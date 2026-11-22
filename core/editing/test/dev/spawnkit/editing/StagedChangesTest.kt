package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StagedChangesTest {
    private class Fixture {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val runtime = builder.build()
        val editor = Editor(runtime)
        val scene =
            editor.project.scenes
                .first()
                .id

        fun add(
            target: (EditOperation) -> OperationResult,
            name: String,
        ): String =
            assertIs<OperationResult.Applied>(
                target(EditOperation.AddObject(scene, name, components = listOf(ComponentDoc("graphics:transform")))),
            ).createdIds.single()
    }

    @Test
    fun stagedStepsLeaveTheWorkspaceAloneUntilAcceptedAsOneUndoableStep() {
        val fixture = Fixture()
        val editor = fixture.editor
        val start = editor.project
        val staged = StagedChanges(editor)
        val coin = fixture.add(staged::apply, "Coin")
        assertIs<OperationResult.Applied>(staged.apply(EditOperation.RenameObject(fixture.scene, coin, "Gold")))
        assertIs<OperationResult.Rejected>(staged.apply(EditOperation.RemoveObject(fixture.scene, "obj-99")))
        assertEquals(listOf("Add Coin", "Rename to Gold"), staged.labels)
        assertEquals(start, editor.project, "Nothing reaches the workspace while staged")
        assertEquals(0L, editor.revision)
        assertTrue(staged.buildWorld(fixture.scene).entity(coin) != null, "Staged objects can be previewed")

        val accepted = assertIs<AcceptResult.Accepted>(staged.accept("Add a gold coin"))
        assertEquals(listOf(coin), accepted.createdIds)
        assertEquals(staged.project.copy(nextId = 0), editor.project.copy(nextId = 0))
        assertEquals("Add a gold coin", editor.undoLabel)
        editor.undo()
        assertEquals(start, editor.project, "Accepting is one undoable step")
        assertFailsWith<IllegalStateException> { staged.apply(EditOperation.RenameProject("Again")) }
    }

    @Test
    fun changesStagedForAnOlderRevisionAreRefusedAndDiscardIsHarmless() {
        val fixture = Fixture()
        val staged = StagedChanges(fixture.editor)
        fixture.add(staged::apply, "Coin")
        fixture.add(fixture.editor::apply, "Wall")
        val after = fixture.editor.project
        assertTrue(assertIs<AcceptResult.Refused>(staged.accept("Add coin")).message.startsWith("The game changed"))
        assertEquals(after, fixture.editor.project)

        val other = StagedChanges(fixture.editor)
        fixture.add(other::apply, "Coin")
        other.discard()
        assertEquals(after, fixture.editor.project)
        assertTrue(other.closed)
        assertIs<AcceptResult.Refused>(StagedChanges(fixture.editor).accept("Nothing"))
    }

    @Test
    fun stagedIdsMatchTheWorkspaceEvenAfterUndoRetiredIds() {
        val fixture = Fixture()
        val editor = fixture.editor
        fixture.add(editor::apply, "Old")
        editor.undo()
        val staged = StagedChanges(editor)
        val coin = fixture.add(staged::apply, "Coin")
        // A later staged step refers to the object created earlier in the same proposal.
        assertIs<OperationResult.Applied>(staged.apply(EditOperation.DuplicateObject(fixture.scene, coin)))
        assertTrue(staged.undoLast())
        assertEquals(listOf("Add Coin"), staged.labels)
        assertIs<OperationResult.Applied>(staged.apply(EditOperation.RenameObject(fixture.scene, coin, "Gold")))
        assertIs<AcceptResult.Accepted>(staged.accept("Coin"))
        assertEquals(
            "Gold",
            editor.project.scenes
                .single()
                .find(coin)
                ?.name,
        )
        assertFalse(
            editor.project.scenes
                .single()
                .objects
                .any { it.name == "Old" },
        )
    }
}
