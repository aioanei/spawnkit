package dev.spawnkit.gameplay

import dev.spawnkit.editing.BuildResult
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Go to scene" is a rule block over a scene-request effect that players act on at the end of a tick (C9). */
class GoToSceneTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val title =
        editor.project.scenes
            .first()
            .id
    private val level = (editor.apply(EditOperation.AddScene("Level 1")) as OperationResult.Applied).createdIds.single()
    private val bonus = (editor.apply(EditOperation.AddScene("Bonus")) as OperationResult.Applied).createdIds.single()
    private val button =
        assertIs<OperationResult.Applied>(
            editor.apply(
                EditOperation.AddObject(
                    title,
                    "Play button",
                    components =
                        listOf(
                            ComponentDoc("graphics:transform", mapOf("x" to 0.0, "y" to 0.0)),
                            ComponentDoc("graphics:shape", mapOf("width" to 2.0, "height" to 1.0)),
                        ),
                ),
            ),
        ).createdIds.single()

    @Test
    fun goToSceneRoundTripsThroughProgramsAndTheCatalog() {
        val rule = Rule(Condition.Tapped, actions = listOf(Action.GoToScene(level)))

        assertEquals(listOf(RuleItem.Blocks(rule)), RuleBlocks.decompile(RuleBlocks.compile(listOf(rule))))
        assertEquals(
            BlockResult.Created(Action.GoToScene(level)),
            RuleBlockCatalog.action(RuleBlockCatalog.picked(Action.GoToScene(level))),
        )
        assertEquals("Go to scene Level 1", RuleBlocks.describe(Action.GoToScene(level), editor.project.scenes))
        assertEquals("Go to a removed scene", RuleBlocks.describe(Action.GoToScene("scene-99"), editor.project.scenes))
    }

    @Test
    fun rulesCannotGoToAMissingScene() {
        val missing = Rule(Condition.Tapped, actions = listOf(Action.GoToScene("scene-99")))

        val result = RuleOperations.addRule(editor.project, title, button, missing)

        assertEquals("Go to scene: choose one of the game's scenes", assertIs<BuildResult.Rejected>(result).message)
    }

    @Test
    fun aTapRequestsTheSceneOnceAndTheLastRequestWins() {
        val rule = Rule(Condition.Tapped, actions = listOf(Action.GoToScene(bonus), Action.GoToScene(level)))
        assertIs<OperationResult.Applied>(editor.apply(RuleOperations.addRule(editor.project, title, button, rule)))
        val built = editor.buildWorld(title)
        assertTrue(built.diagnostics.isEmpty(), built.diagnostics.toString())

        built.world.step()
        assertNull(engine.takeSceneRequest(built.world), "No request before a tap")
        built.world.submitInput(InputFrame(pointers = listOf(Pointer(1, 0.0, 0.0, pressed = true))))
        built.world.step()

        assertEquals(level, engine.takeSceneRequest(built.world))
        assertNull(engine.takeSceneRequest(built.world), "Requests are delivered once")
    }
}
