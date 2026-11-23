package dev.spawnkit.gameplay

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.buildSceneWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** "Leaves the play area" (C14): a fixed box around the camera, the same on every screen, so shots can be cleaned up. */
class PlayAreaTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private fun add(
        name: String,
        components: List<ComponentDoc>,
        vararg rules: Rule,
    ): String {
        val id = assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddObject(scene, name, components = components))).createdIds.single()
        if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules.toList())))
        return id
    }

    private val leaving = Rule(Condition.LeftArea(10.0, 6.0), actions = listOf(Action.RemoveSelf))

    private fun shot(
        x: Double,
        speed: Double,
    ) = add(
        "Shot",
        listOf(
            ComponentDoc("graphics:transform", mapOf("x" to x)),
            ComponentDoc("graphics:shape", mapOf("width" to 1.0, "height" to 1.0)),
            ComponentDoc("gameplay:tag", mapOf("tag" to "shot")),
            ComponentDoc("gameplay:motion", mapOf("vx" to speed)),
        ),
        leaving,
    )

    @Test
    fun theBlockRoundTripsThroughProgramsAndTheCatalog() {
        val rule = Rule(Condition.LeftArea(40.0, 24.0), actions = listOf(Action.RemoveSelf))

        assertEquals(listOf(RuleItem.Blocks(rule)), RuleBlocks.decompile(RuleBlocks.compile(listOf(rule))))
        assertEquals(BlockResult.Created(rule.condition), RuleBlockCatalog.condition(RuleBlockCatalog.picked(rule.condition)))
        assertEquals("Left the play area (40 × 24)", RuleBlocks.describe(rule.condition))
    }

    @Test
    fun aShotIsRemovedOnceItIsWhollyOutsideTheArea() {
        shot(0.0, 60.0)
        val built = buildSceneWorld(engine.runtime, editor.project, scene, engine.programs)

        // The area reaches x = 5; the shot's right edge passes it after 6 units, at one unit per tick.
        repeat(4) { built.world.step() }
        assertEquals(1, engine.gameplay.countTagged(built.world, "shot"), "still overlapping the area")
        repeat(3) { built.world.step() }

        assertEquals(0, engine.gameplay.countTagged(built.world, "shot"))
    }

    @Test
    fun theAreaFollowsTheCamera() {
        add("Camera", listOf(ComponentDoc("graphics:transform", mapOf("x" to 100.0)), ComponentDoc("graphics:camera")))
        shot(100.0, 0.0)
        shot(0.0, 0.0)
        val built = buildSceneWorld(engine.runtime, editor.project, scene, engine.programs)

        built.world.step()
        built.world.step()

        assertEquals(1, engine.gameplay.countTagged(built.world, "shot"), "only the shot near the camera stays")
    }
}
