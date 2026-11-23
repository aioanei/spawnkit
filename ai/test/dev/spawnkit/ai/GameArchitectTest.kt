package dev.spawnkit.ai

import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.Editor
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Game Architect (C13a): a whole game with several scenes is built from one prompt through the builder's own tools,
 * every scene is checked in the real engine, and one undo removes it all. The model is a scripted fixture that reads
 * the IDs earlier tool results created, as a model would; it is not a recording of a model.
 */
class GameArchitectTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val title =
        editor.project.scenes
            .first()
            .id

    /** Plays one step per model call: each step sees the results of earlier calls. */
    private class Fixture(
        vararg steps: (List<ToolReply>) -> List<Pair<String, String>>,
    ) : AgentModel {
        override val info = ModelInfo("Scripted fixture", "none", live = false, label = "Scripted fixture (no model is called)")
        private val steps = ArrayDeque(steps.toList())
        private var next = 0
        val requests = mutableListOf<ModelRequest>()

        override suspend fun reply(request: ModelRequest): ModelReply {
            requests += request
            val replies = request.messages.filterIsInstance<AgentMessage.ToolReplies>().flatMap { it.replies }
            val calls = steps.removeFirst()(replies).map { (name, input) -> ToolCall("c${next++}", name, json(input)) }
            return ModelReply(null, calls)
        }
    }

    private fun List<ToolReply>.created(index: Int): String =
        Json
            .parseToJsonElement(this[index].text)
            .jsonObject["created"]!!
            .jsonArray
            .single()
            .jsonPrimitive.content

    private fun shape(
        x: Int,
        tag: String? = null,
    ): String {
        val tagged = tag?.let { """,{"component":"gameplay:tag","values":{"tag":"$it"}}""" }.orEmpty()
        return """[{"component":"graphics:transform","values":{"x":$x,"y":0}},{"component":"graphics:shape"}$tagged]"""
    }

    @Test
    fun aWholeGameIsBuiltAcrossScenesCheckedAndUndoneInOneStep() =
        runTest {
            val model =
                Fixture(
                    { _ ->
                        listOf(
                            AgentToolbox.EXAMPLE to "{}",
                            "rename_scene" to """{"scene":"$title","name":"Title"}""",
                            "add_scene" to """{"name":"Level 1"}""",
                            "add_scene" to """{"name":"You win"}""",
                        )
                    },
                    { replies ->
                        val level = replies.created(2)
                        val win = replies.created(3)
                        listOf(
                            "add_object" to """{"scene":"$title","name":"Play","components":${shape(0)}}""",
                            "add_object" to """{"scene":"$level","name":"Player","components":${shape(0, tag = "player")}}""",
                            "add_object" to """{"scene":"$level","name":"Goal","components":${shape(3)}}""",
                            "add_object" to """{"scene":"$win","name":"Well done","components":${shape(0)}}""",
                        )
                    },
                    { replies ->
                        val level = replies.created(2)
                        val win = replies.created(3)
                        val (play, player, goal) = listOf(4, 5, 6).map { replies.created(it) }
                        listOf(
                            "add_rule" to
                                """{"scene":"$title","object":"$play","when":{"block":"Tapped"},""" +
                                """"do":[{"block":"GoToScene","scene":"$level"}]}""",
                            "add_rule" to
                                """{"scene":"$level","object":"$player","when":{"block":"KeyHeld","action":"input:right"},""" +
                                """"do":[{"block":"Move","direction":"Right","speed":3}]}""",
                            "add_rule" to
                                """{"scene":"$level","object":"$goal","when":{"block":"Touching","tag":"player"},""" +
                                """"do":[{"block":"GoToScene","scene":"$win"}]}""",
                            AgentToolbox.CHECK to """{"scene":"all"}""",
                            AgentToolbox.TRACE to """{"scene":"$level","seconds":3,"hold":[{"action":"input:right"}]}""",
                        )
                    },
                    { _ -> listOf(AgentToolbox.FINISH to """{"summary":"A title screen, one level and a win screen."}""") },
                )
            val session = AgentSession(engine, editor, title, model)
            val before = editor.project

            val proposal = assertIs<AgentOutcome.Proposal>(session.ask("Make a tiny game where you walk right to the goal."))

            val replies =
                model.requests
                    .last()
                    .messages
                    .filterIsInstance<AgentMessage.ToolReplies>()
                    .flatMap { it.replies }
            val refused = replies.filter { "\"ok\":false" in it.text }
            assertTrue(refused.isEmpty(), refused.joinToString { it.text })
            val check = replies.first { it.name == AgentToolbox.CHECK }.text
            assertEquals(3, check.lines().count { "No problems" in it }, check)
            val trace = replies.first { it.name == AgentToolbox.TRACE }.text
            val goal = trace.substringAfter("Rule 1 of Goal").substringBefore("\n")
            assertTrue("Rule 1 of Goal" in trace && "never ran" !in goal, trace)

            assertIs<AcceptResult.Accepted>(session.accept("Assistant: tiny game"))
            assertEquals(listOf("Title", "Level 1", "You win"), editor.project.scenes.map { it.name })
            assertEquals(10, proposal.steps.size)
            editor.undo()
            assertEquals(before, editor.project, "one undo removes the whole game")
        }

    /** A model whose replies come back empty, as when a long reply is cut off at the output cap, then finishes. */
    private class CutOff(
        private val empties: Int,
    ) : AgentModel {
        override val info = ModelInfo("Scripted fixture", "none", live = false, label = "Scripted fixture (no model is called)")
        val requests = mutableListOf<ModelRequest>()

        override suspend fun reply(request: ModelRequest): ModelReply {
            requests += request
            if (requests.size <= empties) return ModelReply(null, emptyList())
            return ModelReply(null, listOf(ToolCall("f", AgentToolbox.FINISH, json("""{"summary":"Built it."}"""))))
        }
    }

    private companion object {
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun anEmptyReplyIsNotTakenAsDoneTheModelIsAskedToGoOnInSmallerSteps() =
        runTest {
            val model = CutOff(empties = 1)
            val answer = assertIs<AgentOutcome.Answer>(AgentSession(engine, editor, title, model).ask("Make a whole game"))

            assertEquals("Built it.", answer.text)
            val second = model.requests[1].messages
            val empty = second.filterIsInstance<AgentMessage.Assistant>().filter { it.calls.isEmpty() && it.text.isNullOrBlank() }
            assertTrue(empty.isEmpty(), "no empty turns are sent")
            assertTrue((second.last() as AgentMessage.User).text.contains("cut off"))
        }

    @Test
    fun repeatedEmptyRepliesFailPlainlyAndChangeNothing() =
        runTest {
            val before = editor.project
            val failed = assertIs<AgentOutcome.Failed>(AgentSession(engine, editor, title, CutOff(empties = 5)).ask("Make a whole game"))
            assertTrue("came back empty" in failed.message, failed.message)
            assertEquals(before, editor.project)
        }

    @Test
    fun theInstructionsDescribeACompleteGameAndTheBudgetAllowsOne() {
        val system = AgentSession.SYSTEM
        listOf("title screen", "win and lose", "kept", "Test purchase", "set_ads", "look_at_example", "check_game").forEach {
            assertTrue(it in system, "instructions mention $it")
        }
        assertTrue(AgentSession.DEFAULT_MAX_STEPS >= 60)
        assertTrue(KoogModel.DEFAULT_MAX_OUTPUT_TOKENS >= 8192, "replies have room for a batch of building steps")
        assertTrue("15 tool calls" in system)
    }
}
