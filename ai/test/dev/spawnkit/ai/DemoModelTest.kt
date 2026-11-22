package dev.spawnkit.ai

import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Direction
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.gameplay.Target
import dev.spawnkit.runtime.InputFrame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The keyless scripted demo drives the real tools, editor and engine, and is always labelled as a script. */
class DemoModelTest {
    private class Game(
        withObjects: Boolean = true,
    ) {
        val engine = StandardEngine()
        val editor = Editor(engine.runtime, programs = engine.programs)
        val scene =
            editor.project.scenes
                .first()
                .id
        val score = if (withObjects) add("Score", 0.0, text("Score: 0"), ComponentDoc("gameplay:number"), tag("score")) else ""
        val coins =
            if (withObjects) {
                listOf(
                    add("Coin", 3.0, shape(), tag("coin")),
                    add("Coin", 20.0, shape(), tag("coin")),
                )
            } else {
                emptyList()
            }
        val player = if (withObjects) add("Player", 0.0, shape(), tag("player")) else ""

        fun add(
            name: String,
            x: Double,
            vararg components: ComponentDoc,
        ): String {
            val docs = listOf(ComponentDoc("graphics:transform", mapOf("x" to x, "y" to 0.0))) + components
            return assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, components = docs)),
            ).createdIds.single()
        }

        fun rules(id: String): List<RuleItem> =
            RuleBlocks.decompile(
                editor.project
                    .scene(scene)!!
                    .find(id)!!
                    .rules,
            )

        fun moveRightOnHold() {
            val move = Rule(Condition.KeyHeld(StandardActions.RIGHT), actions = listOf(Action.Move(Direction.Right, 3.0)))
            editor.apply(EditOperation.SetRules(scene, player, RuleBlocks.compile(listOf(move))))
        }

        fun scoreTextAfterHoldingRight(seconds: Int): String? {
            val built = editor.buildWorld(scene)
            repeat(seconds * 60) {
                built.world.submitInput(InputFrame(down = setOf(engine.actions.getValue(StandardActions.RIGHT))))
                built.world.step()
            }
            return built.world.get(built.entity(score)!!, engine.graphics.text)?.text
        }

        fun session() = AgentSession(engine, editor, scene, DemoModel())

        private fun tag(name: String) = ComponentDoc("gameplay:tag", mapOf("tag" to name))

        private fun text(value: String) = ComponentDoc("graphics:text", mapOf("text" to value))

        private fun shape() = ComponentDoc("graphics:shape", mapOf("width" to 0.8, "height" to 0.8))
    }

    @Test
    fun collectPromptStagesOneRulePerCoinAndPlaysToScoreOne() =
        runTest {
            val game = Game()
            game.moveRightOnHold()
            val session = game.session()

            val proposal = assertIs<AgentOutcome.Proposal>(session.ask(DemoModel.PROMPTS[0]))

            assertEquals(listOf("Add a rule to Coin", "Add a rule to Coin"), proposal.steps)
            assertEquals(emptyList(), game.rules(game.coins.first()), "Nothing changes before the creator accepts")
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: coins"))
            assertEquals("Score: 1", game.scoreTextAfterHoldingRight(2))
        }

    @Test
    fun movePromptAddsFourKeyRules() =
        runTest {
            val game = Game()
            val session = game.session()

            val proposal = assertIs<AgentOutcome.Proposal>(session.ask(DemoModel.PROMPTS[1]))
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: arrows"))

            assertEquals(List(4) { "Add a rule to Player" }, proposal.steps)
            val keys =
                game.rules(game.player).map { item ->
                    (assertIs<RuleItem.Blocks>(item).rule.condition as Condition.KeyHeld).action
                }
            assertEquals(listOf(StandardActions.LEFT, StandardActions.RIGHT, StandardActions.UP, StandardActions.DOWN), keys)
        }

    @Test
    fun whyPromptAnswersFromARealTrace() =
        runTest {
            val game = Game()
            game.moveRightOnHold()
            val broken = Rule(Condition.Touching("players"), actions = listOf(Action.ChangeNumber(Target.Tagged("score"), 1)))
            game.editor.apply(EditOperation.SetRules(game.scene, game.coins.first(), RuleBlocks.compile(listOf(broken))))
            val before = game.editor.project

            val answer = assertIs<AgentOutcome.Answer>(game.session().ask(DemoModel.PROMPTS[2]))

            assertTrue("never ran" in answer.text, answer.text)
            assertEquals(before, game.editor.project)
        }

    @Test
    fun missingObjectsAndUnknownPromptsExplainWithoutChanges() =
        runTest {
            val game = Game(withObjects = false)
            val before = game.editor.project
            val session = game.session()

            val missing = assertIs<AgentOutcome.Answer>(session.ask(DemoModel.PROMPTS[0]))
            val unknown = assertIs<AgentOutcome.Answer>(session.ask("Make it rain frogs"))

            assertEquals("This demo needs a Player, a Coin and a Score. Add them from the palette, then ask again.", missing.text)
            assertEquals(
                "The scripted demo only knows its example prompts. Add an Anthropic API key in the assistant settings for any request.",
                unknown.text,
            )
            assertEquals(before, game.editor.project)
        }

    @Test
    fun labelSaysNoModelIsCalled() {
        val info = DemoModel().info
        assertFalse(info.live)
        assertEquals("Scripted demo: a fixed script stands in for the AI model (no model is called)", info.label)
        assertEquals(3, DemoModel.PROMPTS.size)
    }
}
