package dev.spawnkit.app.assistant

import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.AgentOutcome
import dev.spawnkit.ai.AgentToolbox
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.ModelInfo
import dev.spawnkit.ai.ModelReply
import dev.spawnkit.ai.ModelRequest
import dev.spawnkit.ai.ToolCall
import dev.spawnkit.ai.WholeGameReplay
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C13b: AI changes apply right away as one undo step with Revert, inside a conversation that stays on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantChatTest {
    private val editor = EditorState()
    private val secrets = MemorySecretStore()

    init {
        listOf("Player" to 0.0, "Coin" to 3.0, "Score" to -4.0).forEach { (name, x) ->
            editor.add(DefaultPresets.first { it.name == name }, x, 0.0)
        }
    }

    private fun coinRules() =
        editor.scene.objects
            .first { it.name == "Coin" }
            .rules

    private fun TestScope.assistant(model: AgentModel? = null): AssistantState {
        if (model != null) secrets.write(AssistantState.KEY_NAME, "sk-test")
        return AssistantState(editor, secrets, this, { model ?: error("No live model in this test") })
    }

    private class Scripted(
        vararg replies: List<ToolCall>,
    ) : AgentModel {
        private val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<ModelRequest>()
        override val info = ModelInfo("Test script", "none", live = false, label = "Test script")

        override suspend fun reply(request: ModelRequest): ModelReply {
            requests += request
            return ModelReply(null, queue.removeFirst())
        }
    }

    private fun call(
        name: String,
        json: String = "{}",
    ) = ToolCall("t-$name", name, Json.parseToJsonElement(json).jsonObject)

    @Test
    fun aProposalIsAppliedRightAwayAsOneUndoStep() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]

            assistant.ask()
            advanceUntilIdle()

            assertNull(assistant.outcome, "Nothing waits for review")
            assertEquals("", assistant.prompt, "The prompt box empties once sent")
            assertEquals(1, coinRules().size)
            val request = assertIs<ChatEntry.Request>(assistant.chat[0])
            assertEquals(DemoModel.PROMPTS[0], request.text)
            val applied = assertIs<ChatEntry.Applied>(assistant.chat[1])
            assertEquals(listOf("Add a rule to Coin"), applied.steps)
            assertTrue(assistant.canRevert(applied))
            editor.undo()
            assertEquals(emptyList(), coinRules(), "Applying is one undo step")
        }

    @Test
    fun revertUndoesTheAssistantsStep() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            val applied = assistant.chat.filterIsInstance<ChatEntry.Applied>().single()

            assistant.revert(applied)

            assertEquals(emptyList(), coinRules())
            assertTrue(assistant.isReverted(applied))
            assertFalse(assistant.canRevert(applied))
            assertEquals("Reverted. Your game is back as it was before.", assistant.notice)
            editor.redo()
            assertEquals(1, coinRules().size, "Revert is an ordinary undo, so Redo brings the change back")
            assertFalse(assistant.isReverted(applied))
            assertTrue(assistant.canRevert(applied), "after Redo it can be reverted again")
        }

    @Test
    fun revertNeverRemovesTheCreatorsLaterWork() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            val applied = assistant.chat.filterIsInstance<ChatEntry.Applied>().single()
            editor.add(DefaultPresets.first { it.name == "Box" }, 1.0, 1.0)
            val objects = editor.scene.objects.size

            assertFalse(assistant.canRevert(applied))
            assistant.revert(applied)

            assertEquals(objects, editor.scene.objects.size)
            assertEquals(1, coinRules().size)
            assertFalse(assistant.isReverted(applied))
            assertEquals(
                "You changed the game after this, so it can't be reverted on its own. Use Undo (↶) to step back.",
                assistant.notice,
            )
        }

    @Test
    fun undoThenRedoMakesTheStepRevertibleAgain() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            val applied = assistant.chat.filterIsInstance<ChatEntry.Applied>().single()

            editor.undo()
            assertFalse(assistant.canRevert(applied))
            editor.redo()

            assertTrue(assistant.canRevert(applied))
        }

    @Test
    fun editingWhileTheAssistantWorksKeepsTheCreatorsChange() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val demo = DemoModel()
            val slow =
                object : AgentModel {
                    override val info = demo.info

                    override suspend fun reply(request: ModelRequest): ModelReply {
                        gate.await()
                        return demo.reply(request)
                    }
                }
            val assistant = assistant(slow)
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            val coin = editor.scene.objects.first { it.name == "Coin" }
            editor.apply(EditOperation.RenameObject(editor.sceneId, coin.id, "Gold"))

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("Gold", editor.scene.find(coin.id)?.name)
            assertEquals(emptyList(), editor.scene.find(coin.id)?.rules)
            assertTrue(assistant.chat.none { it is ChatEntry.Applied })
            assertTrue("changed while" in assistant.notice.orEmpty(), assistant.notice)
            assertFalse(assistant.running)
        }

    /** A demo model that waits at its last reply (the summary) until [gate] opens, so a test can look mid-work. */
    private class Paused(
        private val gate: CompletableDeferred<Unit>,
    ) : AgentModel {
        private val demo = DemoModel()
        private var calls = 0
        override val info = demo.info

        override suspend fun reply(request: ModelRequest): ModelReply {
            if (++calls == 3) gate.await()
            return demo.reply(request)
        }
    }

    @Test
    fun theCanvasShowsTheAssistantsWorkWhileItRuns() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val assistant = assistant(Paused(gate))
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()

            val preview = editor.assistantPreview
            assertTrue(assistant.running)
            assertEquals(
                1,
                preview
                    ?.scenes
                    ?.first()
                    ?.objects
                    ?.first { it.name == "Coin" }
                    ?.rules
                    ?.size,
                "the staged rule is shown",
            )
            assertEquals(emptyList(), coinRules(), "the game itself is unchanged until the step is applied")
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(editor.assistantPreview)
            assertEquals(1, coinRules().size)
        }

    @Test
    fun withReviewFirstThePreviewStaysUntilAcceptOrReject() =
        runTest {
            val assistant = assistant()
            assistant.reviewFirst = true
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()

            assertTrue(editor.assistantPreview != null, "the proposal is previewed on the canvas")
            assistant.reject()
            assertNull(editor.assistantPreview)

            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            assistant.accept()
            assertNull(editor.assistantPreview)
            assertEquals(1, coinRules().size)
        }

    @Test
    fun cancellingClearsThePreview() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val assistant = assistant(Paused(gate))
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            assertTrue(editor.assistantPreview != null)

            assistant.cancel()
            advanceUntilIdle()

            assertNull(editor.assistantPreview)
            assertEquals(emptyList(), coinRules())
        }

    @Test
    fun reviewFirstStillWaitsForAccept() =
        runTest {
            val assistant = assistant()
            assistant.reviewFirst = true
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()

            assertIs<AgentOutcome.Proposal>(assistant.outcome)
            assertEquals(emptyList(), coinRules())
            assistant.accept()

            assertEquals(1, coinRules().size)
            val applied = assistant.chat.filterIsInstance<ChatEntry.Applied>().single()
            assertTrue(assistant.canRevert(applied), "Accepted changes can be reverted too")
        }

    @Test
    fun answersJoinTheConversation() =
        runTest {
            val script =
                Scripted(
                    listOf(call(AgentToolbox.ASK, """{"question":"Which coin?","options":["Coin","All coins"]}""")),
                    listOf(call(AgentToolbox.FINISH, """{"summary":"Nothing to change."}""")),
                )
            val assistant = assistant(script)
            assistant.prompt = "Change the coin"
            assistant.ask()
            advanceUntilIdle()
            assertIs<AgentOutcome.Question>(assistant.outcome)

            assistant.answer("All coins")
            advanceUntilIdle()

            assertNull(assistant.outcome)
            assertEquals(
                listOf("Change the coin", "Which coin?", "All coins", "Nothing to change."),
                assistant.chat.map { it.text },
            )
        }

    @Test
    fun revertTellsTheModel() =
        runTest {
            val requests = mutableListOf<ModelRequest>()
            val demo = DemoModel()
            val watched =
                object : AgentModel {
                    override val info = demo.info

                    override suspend fun reply(request: ModelRequest): ModelReply = demo.reply(request).also { requests += request }
                }
            val assistant = assistant(watched)
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            assistant.revert(assistant.chat.filterIsInstance<ChatEntry.Applied>().single())

            assistant.prompt = "What now?"
            assistant.ask()
            advanceUntilIdle()

            val told = requests.last().messages.toString()
            assertTrue("reverted" in told, told)
        }

    @Test
    fun theChatSurvivesASceneChangeAndANewProjectClearsIt() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            editor.applyCatalog("add_scene", mapOf("name" to "Level 2"))
            editor.selectScene(
                editor.project.scenes
                    .last()
                    .id,
            )
            assistant.refresh()

            assertEquals(2, assistant.chat.count { it !is ChatEntry.Note })

            editor.loadProject(Project.blank("Other game"))
            assistant.refresh()

            assertTrue(assistant.chat.isEmpty())
        }

    @Test
    fun theWholeGameReplayBuildsJungleBananaRunLabelledOnABlankGame() =
        runTest {
            editor.loadProject(Project.blank())
            val assistant = assistant()
            val labels = mutableListOf<String>()

            assistant.watchReplay(pauseMillis = 0)
            labels += assistant.modelLabel
            advanceUntilIdle()

            assertTrue(labels.single().startsWith("Replay: claude-sonnet-5-5"), labels.single())
            assertEquals(6, editor.project.scenes.size)
            assertTrue(
                assistant.chat
                    .first()
                    .text
                    .startsWith("Replay of a recorded session"),
            )
            assertEquals(WholeGameReplay.prompt, assistant.chat[1].text)
            val applied = assertIs<ChatEntry.Applied>(assistant.chat.last())
            assertTrue(applied.replay)
            assertEquals(DemoModel.LABEL, assistant.modelLabel, "after the replay, the usual model answers again")
            assertEquals("No cost (no model is called)", assistant.costText)

            assistant.revert(applied)
            assertEquals(1, editor.project.scenes.size, "the whole replayed game is one undo step")
        }
}
