package dev.spawnkit.editing

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.program.BinaryOp
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.ProgramFeature
import dev.spawnkit.program.ProgramHostBuilder
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.Trigger
import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorRulesTest {
    private class Fixture {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val programs = ProgramFeature(builder, ProgramHostBuilder().properties(graphics.transformDescriptor).build())
        val runtime = builder.build()
        val editor = Editor(runtime, programs = programs)
        val scene: String get() =
            editor.project.scenes
                .first()
                .id

        fun add(name: String): String =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, components = listOf(ComponentDoc("graphics:transform")))),
            ).createdIds.single()

        fun x(
            world: SceneWorld,
            objectId: String,
        ): Double = checkNotNull(world.world.get(checkNotNull(world.entity(objectId)), graphics.transform)).x
    }

    private fun s(id: String) = SourceRef(id, id)

    /** Every tick, add [step] to this object's x. */
    private fun moveRule(
        step: Double,
        property: String = "x",
    ) = Handler(
        Trigger.EveryTick,
        listOf(
            Stmt.SetProperty(
                Expr.Self(s("self")),
                property,
                Expr.Binary(
                    BinaryOp.Add,
                    Expr.GetProperty(Expr.Self(s("self")), "x", s("get")),
                    Expr.Literal(DecimalValue(step), s("step")),
                    s("add"),
                ),
                s("block:move"),
            ),
        ),
        s("rule:move"),
    )

    @Test
    fun rulesAreSetAsOneUndoableStepAndCopiedWithTheObject() {
        val fixture = Fixture()
        val editor = fixture.editor
        val hero = fixture.add("Hero")
        val result = editor.apply(EditOperation.SetRules(fixture.scene, hero, listOf(moveRule(1.0))))
        assertIs<OperationResult.Applied>(result)
        assertEquals(
            listOf(moveRule(1.0)),
            editor.project
                .scene(fixture.scene)
                ?.find(hero)
                ?.rules,
        )
        assertTrue(editor.undoLabel?.contains("rules") == true, editor.undoLabel)
        editor.undo()
        assertEquals(
            emptyList(),
            editor.project
                .scene(fixture.scene)
                ?.find(hero)
                ?.rules,
        )
        editor.redo()
        val copy =
            assertIs<OperationResult.Applied>(editor.apply(EditOperation.DuplicateObject(fixture.scene, hero)))
                .createdIds
                .single()
        assertEquals(
            listOf(moveRule(1.0)),
            editor.project
                .scene(fixture.scene)
                ?.find(copy)
                ?.rules,
        )
        assertIs<OperationResult.Rejected>(editor.apply(EditOperation.SetRules(fixture.scene, "obj-999", emptyList())))
    }

    @Test
    fun playWorldsRunEachObjectsRules() {
        val fixture = Fixture()
        val hero = fixture.add("Hero")
        val idle = fixture.add("Idle")
        fixture.editor.apply(EditOperation.SetRules(fixture.scene, hero, listOf(moveRule(1.0))))
        val world = fixture.editor.buildWorld(fixture.scene)
        assertTrue(world.diagnostics.isEmpty())
        repeat(3) { world.world.step() }
        assertEquals(3.0, fixture.x(world, hero))
        assertEquals(0.0, fixture.x(world, idle), "Objects without rules are unaffected")
        assertNull(world.world.get(checkNotNull(world.entity(idle)), fixture.programs.behavior))
    }

    @Test
    fun editingRulesAndRebuildingNeverConflicts() {
        val fixture = Fixture()
        val hero = fixture.add("Hero")
        for (step in listOf(1.0, 2.0, 1.0)) {
            fixture.editor.apply(EditOperation.SetRules(fixture.scene, hero, listOf(moveRule(step))))
            val world = fixture.editor.buildWorld(fixture.scene)
            world.world.step()
            assertEquals(step, fixture.x(world, hero), "The rebuilt world runs the current rules")
            // A checkpoint of a play world restores with the program version it was built with.
            val restored = fixture.runtime.restore(world.world.snapshot())
            assertEquals(world.world.stateHash(), restored.stateHash())
        }
    }

    @Test
    fun invalidRulesAreReportedOnTheirObjectAndSkippedWhilePlaying() {
        val fixture = Fixture()
        val broken = fixture.add("Broken")
        val hero = fixture.add("Hero")
        fixture.editor.apply(EditOperation.SetRules(fixture.scene, broken, listOf(moveRule(1.0, property = "nope"))))
        fixture.editor.apply(EditOperation.SetRules(fixture.scene, hero, listOf(moveRule(1.0))))
        val world = fixture.editor.buildWorld(fixture.scene)
        val problems = world.diagnostics.getValue(broken)
        assertEquals("block:move", problems.single().source.nodeId)
        assertEquals(setOf(broken), world.diagnostics.keys)
        world.world.step()
        assertEquals(0.0, fixture.x(world, broken), "Rules with problems do not run")
        assertEquals(1.0, fixture.x(world, hero), "Other objects still play")
    }

    @Test
    fun withoutAProgramFeatureRulesAreKeptButNotRun() {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val editor = Editor(builder.build())
        val scene =
            editor.project.scenes
                .first()
                .id
        val hero =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, "Hero", components = listOf(ComponentDoc("graphics:transform")))),
            ).createdIds.single()
        editor.apply(EditOperation.SetRules(scene, hero, listOf(moveRule(1.0))))
        val world = editor.buildWorld(scene)
        world.world.step()
        assertEquals(0.0, world.world.get(checkNotNull(world.entity(hero)), graphics.transform)?.x)
        assertEquals(
            1,
            editor.project
                .scene(scene)
                ?.find(hero)
                ?.rules
                ?.size,
        )
    }
}
