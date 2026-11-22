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
import dev.spawnkit.gameplay.RuleOperations
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.gameplay.Target
import dev.spawnkit.runtime.InputFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assistant sessions replayed from hand-written fixtures. Fixtures stand in for a model's choices only: every tool call
 * runs against the real editor, catalog and engine, and each replay is labelled as a scripted fixture, never as a live
 * or recorded model (AGENTS.md).
 */
class AgentSessionTest {
    private class Game {
        val engine = StandardEngine()
        val editor = Editor(engine.runtime, programs = engine.programs)
        val scene =
            editor.project.scenes
                .first()
                .id
        val score =
            add("Score", 0.0, ComponentDoc("graphics:text", mapOf("text" to "Score: 0")), ComponentDoc("gameplay:number"), tag("score"))
        val coin = add("Coin", 3.0, shape(0.6), tag("coin"))
        val player = add("Player", 0.0, shape(0.9), tag("player"))

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

        fun rules(id: String) =
            editor.project
                .scene(scene)!!
                .find(id)!!
                .rules

        /** Plays [seconds] holding Right; returns the score object's text and number. */
        fun playHoldingRight(seconds: Int): Pair<String?, Long?> {
            val built = editor.buildWorld(scene)
            repeat(seconds * 60) {
                built.world.submitInput(InputFrame(down = setOf(engine.actions.getValue(StandardActions.RIGHT))))
                built.world.step()
            }
            val entity = built.entity(score)!!
            return built.world.get(entity, engine.graphics.text)?.text to built.world.get(entity, engine.gameplay.number)?.value
        }

        private fun tag(name: String) = ComponentDoc("gameplay:tag", mapOf("tag" to name))

        private fun shape(size: Double) = ComponentDoc("graphics:shape", mapOf("width" to size, "height" to size))
    }

    private var callCounter = 0

    private fun call(
        name: String,
        json: String = "{}",
    ) = ToolCall("call-${callCounter++}", name, Json.parseToJsonElement(json).jsonObject)

    private fun replies(vararg calls: List<ToolCall>): List<ModelReply> = calls.map { ModelReply(null, it, Usage(1_000, 100)) }

    private fun fixture(
        script: List<ScriptStep>,
        replies: List<ModelReply>,
    ) = RecordedSession(
        "Scripted fixture",
        "none",
        "2026-11-21",
        "Written by hand to exercise replay; not a recording of a model.",
        script,
        replies,
    )

    private fun AgentSession.toolText(name: String): String =
        transcript
            .filterIsInstance<AgentMessage.ToolReplies>()
            .flatMap { it.replies }
            .last { it.name == name }
            .text

    @Test
    fun scoreAndCollectIsBuiltFromPromptsAloneAndMatchesTheManualRules() =
        runTest {
            val game = Game()
            val collect = "When the player touches a coin, add 1 to the score, show it and remove the coin."
            val move = "Make the player move right while I hold Right."
            val recording =
                fixture(
                    listOf(ScriptStep("prompt", collect), ScriptStep("prompt", move)),
                    replies(
                        listOf(call(AgentToolbox.LOOK)),
                        listOf(
                            call(
                                "add_rule",
                                """{"scene":"${game.scene}","object":"${game.coin}","when":{"block":"Touching","tag":"player"},
                                "do":[{"block":"ChangeNumber","target":{"kind":"tagged","tag":"score"},"amount":1},
                                {"block":"ShowNumber","target":{"kind":"tagged","tag":"score"},"prefix":"Score: "},{"block":"RemoveSelf"}]}""",
                            ),
                        ),
                        listOf(call(AgentToolbox.FINISH, """{"summary":"Coins add 1 to the score, update it and disappear."}""")),
                        listOf(call(AgentToolbox.LOOK)),
                        listOf(
                            call(
                                "add_rule",
                                """{"scene":"${game.scene}","object":"${game.player}","when":{"block":"KeyHeld","action":"input:right"},
                                "do":[{"block":"Move","direction":"Right","speed":3}]}""",
                            ),
                        ),
                        listOf(
                            call(AgentToolbox.TRACE, """{"seconds":2,"hold":[{"action":"input:right","from":0,"to":2}]}"""),
                            call(AgentToolbox.CHECK),
                        ),
                        listOf(call(AgentToolbox.FINISH, """{"summary":"The player moves right while you hold Right."}""")),
                    ),
                )
            val decoded = RecordedSession.decode(RecordedSession.encode(recording))
            assertEquals(recording, decoded, "Recordings round-trip")
            val model = ReplayModel(decoded)
            assertFalse(model.info.live)
            assertTrue(model.info.label.startsWith("Replay: Scripted fixture, recorded 2026-11-21"))
            val session = AgentSession(game.engine, game.editor, game.scene, model)
            val events = mutableListOf<AgentEvent>()

            val first = assertIs<AgentOutcome.Proposal>(session.ask(collect, events::add))
            assertEquals(listOf("Add a rule to Coin"), first.steps)
            assertEquals(emptyList(), game.rules(game.coin), "Nothing changes before the creator accepts")
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: coins add to the score"))
            val second = assertIs<AgentOutcome.Proposal>(session.ask(move, events::add))
            assertTrue(
                "Rule 1 of Coin (When touching player) ran once" in session.toolText(AgentToolbox.TRACE),
                session.toolText(AgentToolbox.TRACE),
            )
            assertEquals("No problems: every object's rules are ready to run.", session.toolText(AgentToolbox.CHECK))
            assertEquals("The player moves right while you hold Right.", second.summary)
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: player moves right"))
            assertTrue(model.finished)
            assertTrue(events.any { it is AgentEvent.UsedTool && it.staged == "Add a rule to Player" })
            assertNull(session.cost.dollars, "Replays cost nothing")
            assertEquals(Usage(7_000, 700), session.cost.usage)

            // The same rules built through the builder's own rule operations.
            val manual = Game()

            fun built(result: dev.spawnkit.editing.BuildResult) = assertIs<OperationResult.Applied>(manual.editor.apply(result))
            built(
                RuleOperations.addRule(
                    manual.editor.project,
                    manual.scene,
                    manual.coin,
                    Rule(
                        Condition.Touching("player"),
                        actions =
                            listOf(
                                Action.ChangeNumber(Target.Tagged("score"), 1),
                                Action.ShowNumber(Target.Tagged("score"), "Score: "),
                                Action.RemoveSelf,
                            ),
                    ),
                ),
            )
            built(
                RuleOperations.addRule(
                    manual.editor.project,
                    manual.scene,
                    manual.player,
                    Rule(Condition.KeyHeld(StandardActions.RIGHT), actions = listOf(Action.Move(Direction.Right, 3.0))),
                ),
            )
            assertEquals(manual.editor.project.copy(nextId = 0), game.editor.project.copy(nextId = 0))
            assertEquals("Score: 1" to 1L, game.playHoldingRight(2))
            game.editor.undo()
            assertEquals(emptyList(), game.rules(game.player), "Each accepted proposal is one undo step")
        }

    @Test
    fun aNonFiringRuleIsExplainedFromATraceAndCoinsAreMadeWorthFive() =
        runTest {
            val game = Game()
            val broken =
                Rule(Condition.Touching("players"), actions = listOf(Action.ChangeNumber(Target.Tagged("score"), 1), Action.RemoveSelf))
            val move = Rule(Condition.KeyHeld(StandardActions.RIGHT), actions = listOf(Action.Move(Direction.Right, 3.0)))
            game.editor.apply(EditOperation.SetRules(game.scene, game.coin, RuleBlocks.compile(listOf(broken))))
            game.editor.apply(EditOperation.SetRules(game.scene, game.player, RuleBlocks.compile(listOf(move))))
            val explanation = "Your coin waits for an object tagged \"players\", but the player is tagged \"player\"."
            val recording =
                fixture(
                    listOf(ScriptStep("prompt", "Why doesn't my coin rule fire?"), ScriptStep("prompt", "Fix it and make coins worth 5.")),
                    replies(
                        listOf(call(AgentToolbox.LOOK), call(AgentToolbox.TRACE, """{"seconds":2,"hold":[{"action":"input:right"}]}""")),
                    ) + ModelReply(explanation, emptyList()) +
                        replies(
                            listOf(
                                call(
                                    "change_block",
                                    """{"scene":"${game.scene}","object":"${game.coin}","rule":1,"part":"when","block":{"block":"Touching","tag":"player"}}""",
                                ),
                                call(
                                    "change_block",
                                    """{"scene":"${game.scene}","object":"${game.coin}","rule":1,"part":"do","position":1,
                                    "block":{"block":"ChangeNumber","target":{"kind":"tagged","tag":"score"},"amount":5}}""",
                                ),
                            ),
                            listOf(call(AgentToolbox.FINISH, """{"summary":"Coins now react to the player and are worth 5."}""")),
                        ),
                )
            val session = AgentSession(game.engine, game.editor, game.scene, ReplayModel(recording))
            val answer = assertIs<AgentOutcome.Answer>(session.ask("Why doesn't my coin rule fire?"))
            assertEquals(explanation, answer.text)
            val trace = session.toolText(AgentToolbox.TRACE)
            assertTrue("Rule 1 of Coin (When touching players) never ran: \"Touching players\" was never true." in trace, trace)
            assertTrue("Rule 1 of Player (When holding right) ran 120 times" in trace, trace)
            assertNull(session.changes, "An explanation stages nothing")

            val fix = assertIs<AgentOutcome.Proposal>(session.ask("Fix it and make coins worth 5."))
            assertEquals(listOf("Change a block in rule 1 of Coin", "Change a block in rule 1 of Coin"), fix.steps)
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: fix coins"))
            assertEquals(5L, game.playHoldingRight(2).second)
        }

    @Test
    fun anAmbiguousPromptGetsAClarifyingQuestionAndTheAnswerContinuesTheProposal() =
        runTest {
            val game = Game()
            val high = game.add("High score", 5.0, ComponentDoc("gameplay:number"), ComponentDoc("gameplay:tag", mapOf("tag" to "best")))
            val recording =
                fixture(
                    listOf(ScriptStep("prompt", "Add 1 to the score when the coin is tapped."), ScriptStep("answer", "Score")),
                    replies(
                        listOf(
                            call(AgentToolbox.LOOK),
                            call(AgentToolbox.ASK, """{"question":"Which score: Score or High score?","options":["Score","High score"]}"""),
                            call(AgentToolbox.CHECK),
                        ),
                        listOf(
                            call(
                                "add_rule",
                                """{"scene":"${game.scene}","object":"${game.coin}","when":{"block":"Tapped"},
                                "do":[{"block":"ChangeNumber","target":{"kind":"tagged","tag":"score"}}]}""",
                            ),
                            call(AgentToolbox.FINISH, """{"summary":"Tapping the coin adds 1 to Score."}"""),
                        ),
                    ),
                )
            val session = AgentSession(game.engine, game.editor, game.scene, ReplayModel(recording))
            val question = assertIs<AgentOutcome.Question>(session.ask("Add 1 to the score when the coin is tapped."))
            assertEquals("Which score: Score or High score?", question.question)
            assertEquals(listOf("Score", "High score"), question.options)
            val proposal = assertIs<AgentOutcome.Proposal>(session.answer("Score"))
            assertEquals(listOf("Add a rule to Coin"), proposal.steps)
            val replies =
                session.transcript.filterIsInstance<AgentMessage.ToolReplies>().first { r ->
                    r.replies.any {
                        it.name ==
                            AgentToolbox.ASK
                    }
                }
            assertEquals(
                listOf(AgentToolbox.LOOK, AgentToolbox.ASK, AgentToolbox.CHECK),
                replies.replies.map {
                    it.name
                },
                "Every call is answered once",
            )
            assertEquals("The creator answered: Score", replies.replies[1].text)
            session.reject()
            assertEquals(emptyList(), game.rules(game.coin), "Rejecting discards the proposal")
            assertTrue(game.rules(high).isEmpty())
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // advanceUntilIdle
    @Test
    fun cancellingRefusalsAndStaleProposalsNeverChangeTheGame() =
        runTest {
            val game = Game()
            val start = game.editor.project
            val gate = CompletableDeferred<ModelReply>()
            val hanging =
                object : AgentModel {
                    override val info = ModelInfo("test", "test", live = true, label = "Test model", 1.0, 2.0)

                    override suspend fun reply(request: ModelRequest): ModelReply = gate.await()
                }
            val session = AgentSession(game.engine, game.editor, game.scene, hanging)
            val job = launch { session.ask("Do something slow") }
            advanceUntilIdle()
            job.cancel()
            advanceUntilIdle()
            assertTrue(job.isCancelled)
            assertNull(session.changes)
            assertEquals(start, game.editor.project)

            // A refused call is explained to the model and stages nothing; a proposal for an older revision is refused.
            val recording =
                fixture(
                    listOf(ScriptStep("prompt", "Rename the dragon")),
                    replies(
                        listOf(call("rename_object", """{"scene":"${game.scene}","object":"obj-99","name":"Dragon"}""")),
                        listOf(call("rename_object", """{"scene":"${game.scene}","object":"${game.coin}","name":"Gold"}""")),
                        listOf(call(AgentToolbox.FINISH, """{"summary":"Renamed the coin to Gold."}""")),
                    ),
                )
            val replay = AgentSession(game.engine, game.editor, game.scene, ReplayModel(recording))
            val proposal = assertIs<AgentOutcome.Proposal>(replay.ask("Rename the dragon"))
            val refusal =
                replay.transcript
                    .filterIsInstance<AgentMessage.ToolReplies>()
                    .first()
                    .replies
                    .single()
                    .text
            assertTrue("There is no object 'obj-99'" in refusal, refusal)
            assertEquals(listOf("Rename to Gold"), proposal.steps)
            game.editor.apply(EditOperation.RenameProject("Changed meanwhile"))
            assertTrue(assertIs<AcceptResult.Refused>(replay.accept("Rename")).message.startsWith("The game changed"))
            assertEquals(
                "Coin",
                game.editor.project
                    .scene(game.scene)!!
                    .find(game.coin)!!
                    .name,
            )

            // Recordings that call tools which no longer exist stop with an explanation and stage nothing.
            val stale = fixture(listOf(ScriptStep("prompt", "Old")), replies(listOf(call("teleport_object"))))
            val failed = assertIs<AgentOutcome.Failed>(AgentSession(game.engine, game.editor, game.scene, ReplayModel(stale)).ask("Old"))
            assertTrue("teleport_object" in failed.message)
            assertFailsWith<IllegalArgumentException> { RecordedSession.decode("""{"format":"other"}""") }
            assertEquals(Cost(Usage(1_000_000, 1_000_000), 3.0), Cost.of(Usage(1_000_000, 1_000_000), hanging.info))
        }
}
