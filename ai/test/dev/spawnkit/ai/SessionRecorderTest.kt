package dev.spawnkit.ai

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session recorder plays the creator: it sends prompts, answers questions with their first option and accepts each
 * proposal, recording the model's replies so the session replays without a key (C7, live recordings).
 */
class SessionRecorderTest {
    private class Game {
        val engine = StandardEngine()
        val editor = Editor(engine.runtime, programs = engine.programs)
        val scene =
            editor.project.scenes
                .first()
                .id

        init {
            add("Player", ComponentDoc("graphics:shape"), tag("player"))
            add("Coin", ComponentDoc("graphics:shape"), tag("coin"))
            add("Score", ComponentDoc("graphics:text", mapOf("text" to "Score: 0")), ComponentDoc("gameplay:number"), tag("score"))
        }

        private fun tag(name: String) = ComponentDoc("gameplay:tag", mapOf("tag" to name))

        private fun add(
            name: String,
            vararg components: ComponentDoc,
        ) = check(
            editor.apply(
                EditOperation.AddObject(scene, name, components = listOf(ComponentDoc("graphics:transform")) + components),
            ) is OperationResult.Applied,
        )

        fun recorder() = SessionRecorder(engine, editor, scene)
    }

    /** Asks one question with options A and B, then finishes without changes. */
    private class AskingModel : AgentModel {
        override val info = ModelInfo("Asking fake", "none", live = false, label = "Asking fake")
        private var calls = 0

        override suspend fun reply(request: ModelRequest): ModelReply {
            val answered = request.messages.lastOrNull() is AgentMessage.ToolReplies
            val call =
                if (answered) {
                    ToolCall("c${calls++}", AgentToolbox.FINISH, JsonObject(mapOf("summary" to JsonPrimitive("Thanks"))))
                } else {
                    ToolCall(
                        "c${calls++}",
                        AgentToolbox.ASK,
                        JsonObject(
                            mapOf(
                                "question" to JsonPrimitive("Which?"),
                                "options" to JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B"))),
                            ),
                        ),
                    )
                }
            return ModelReply(null, listOf(call))
        }
    }

    @Test
    fun recordsEveryPromptAndAcceptsEachProposal() =
        runTest {
            val game = Game()
            val prompts = DemoModel.PROMPTS.take(2)

            val run = game.recorder().record(DemoModel(), prompts, "2026-11-22", "Demo check")

            assertEquals(prompts.map { ScriptStep("prompt", it) }, run.recording.script)
            assertTrue(run.recording.replies.isNotEmpty())
            assertEquals(2, run.log.count { it.startsWith("Proposal") }, run.log.joinToString("\n"))
            assertEquals(
                2,
                game.editor.project.scenes
                    .first()
                    .objects
                    .count { it.rules.isNotEmpty() },
                "coin and player rules were accepted",
            )
            assertEquals("Assistant: ${prompts[1]}", game.editor.undoLabel)
        }

    @Test
    fun aRecordingReplaysToTheSameGame() =
        runTest {
            val recorded = Game()
            val run = recorded.recorder().record(DemoModel(), DemoModel.PROMPTS.take(2), "2026-11-22", "Demo check")
            val replayed = Game()

            val log = replayed.recorder().replay(RecordedSession.decode(RecordedSession.encode(run.recording)))

            assertEquals(recorded.editor.project, replayed.editor.project)
            assertEquals(run.log, log)
        }

    @Test
    fun aQuestionIsAnsweredWithItsFirstOptionAndRecorded() =
        runTest {
            val run = Game().recorder().record(AskingModel(), listOf("Do something"), "2026-11-22", "Question check")

            assertEquals(listOf(ScriptStep("prompt", "Do something"), ScriptStep("answer", "A")), run.recording.script)
            assertEquals(listOf("Question: Which? → answered \"A\"", "Answer: Thanks"), run.log)
            assertEquals(run.log, Game().recorder().replay(run.recording), "the recorded answer replays too")
        }

    @Test
    fun aFailureStopsTheRecording() =
        runTest {
            val failing =
                object : AgentModel {
                    override val info = ModelInfo("Failing fake", "none", live = false, label = "Failing fake")

                    override suspend fun reply(request: ModelRequest): ModelReply = error("offline")
                }

            val run = Game().recorder().record(failing, listOf("First", "Second"), "2026-11-22", "Failure check")

            assertEquals(listOf(ScriptStep("prompt", "First")), run.recording.script)
            assertEquals(listOf("Failed: The assistant could not answer: offline"), run.log)
        }
}
