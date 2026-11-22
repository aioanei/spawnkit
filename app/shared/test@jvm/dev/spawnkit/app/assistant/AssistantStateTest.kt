package dev.spawnkit.app.assistant

import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.AgentOutcome
import dev.spawnkit.ai.AgentToolbox
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.HostedModelException
import dev.spawnkit.ai.ModelInfo
import dev.spawnkit.ai.ModelReply
import dev.spawnkit.ai.ModelRequest
import dev.spawnkit.ai.ToolCall
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

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantStateTest {
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

    private fun TestScope.assistant(
        hosted: () -> HostedChoice? = { null },
        live: (String) -> AgentModel = { error("No live model in this test") },
    ) = AssistantState(editor, secrets, this, live, hosted)

    /** A model that answers with fixed tool calls, one reply per call to [reply]. */
    private class Scripted(
        vararg replies: List<ToolCall>,
        override val info: ModelInfo = ModelInfo("Test script", "none", live = false, label = "Test script"),
    ) : AgentModel {
        private val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<ModelRequest>()

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
    fun proposalChangesNothingUntilAcceptedAndUndoesInOneStep() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]

            assistant.ask()
            advanceUntilIdle()

            val proposal = assertIs<AgentOutcome.Proposal>(assistant.outcome)
            assertEquals(listOf("Add a rule to Coin"), proposal.steps)
            assertTrue("Staged: Add a rule to Coin" in assistant.progress, assistant.progress.toString())
            assertEquals(emptyList(), coinRules())
            assertEquals(DemoModel.LABEL, assistant.modelLabel)
            assertEquals("No cost (no model is called)", assistant.costText)

            assistant.accept()

            assertNull(assistant.outcome)
            assertEquals(1, coinRules().size)
            editor.undo()
            assertEquals(emptyList(), coinRules(), "Accepting is one undo step")
        }

    @Test
    fun rejectAndCancelLeaveTheGameUnchanged() =
        runTest {
            val before = editor.project
            val gate = CompletableDeferred<ModelReply>()
            val live: AgentModel =
                object : AgentModel {
                    override val info = ModelInfo("Slow", "test", live = true, label = "Live: slow (test)")

                    override suspend fun reply(request: ModelRequest): ModelReply = gate.await()
                }
            val assistant = assistant { live }
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            assistant.reject()
            assertNull(assistant.outcome)
            assertEquals(before, editor.project)

            assistant.saveKey("sk-test")
            assistant.useLive = true
            assistant.prompt = "Make coins worth 5"
            assistant.ask()
            advanceUntilIdle()
            assertTrue(assistant.running)
            assistant.cancel()
            advanceUntilIdle()

            assertFalse(assistant.running)
            assertEquals("Cancelled. Nothing in your game changed.", assistant.notice)
            assertEquals(before, editor.project)
        }

    @Test
    fun questionShowsOptionsAndAnswerContinues() =
        runTest {
            val script =
                Scripted(
                    listOf(call(AgentToolbox.ASK, """{"question":"Which coin?","options":["Coin","All coins"]}""")),
                    listOf(call(AgentToolbox.FINISH, """{"summary":"Nothing to change."}""")),
                )
            secrets.write(AssistantState.KEY_NAME, "sk-test")
            val assistant = assistant { script }
            assistant.prompt = "Change the coin"

            assistant.ask()
            advanceUntilIdle()
            val question = assertIs<AgentOutcome.Question>(assistant.outcome)
            assertEquals(listOf("Coin", "All coins"), question.options)
            assistant.answer("All coins")
            advanceUntilIdle()

            assertEquals("Nothing to change.", assertIs<AgentOutcome.Answer>(assistant.outcome).text)
        }

    @Test
    fun askIsDisabledWhilePlayingOrBlank() =
        runTest {
            val assistant = assistant()
            assistant.prompt = "  "
            assertFalse(assistant.canAsk())

            assistant.prompt = DemoModel.PROMPTS[0]
            assertTrue(assistant.canAsk())
            editor.startPlay()
            assertFalse(assistant.canAsk())
            editor.stopPlay()
            assertTrue(assistant.canAsk())
        }

    @Test
    fun loadingAnotherProjectDropsTheProposal() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            assertIs<AgentOutcome.Proposal>(assistant.outcome)
            val other = Project.blank("Other game")

            editor.loadProject(other)
            assistant.accept()

            assertNull(assistant.outcome)
            assertEquals("Your game changed, so the proposal was dropped. Nothing was applied.", assistant.notice)
            assertEquals("Other game", editor.project.name)
            assertTrue(editor.scene.objects.isEmpty())
        }

    @Test
    fun answeringAfterTheProjectChangedDropsTheQuestion() =
        runTest {
            val script = Scripted(listOf(call(AgentToolbox.ASK, """{"question":"Which coin?","options":["Coin"]}""")))
            secrets.write(AssistantState.KEY_NAME, "sk-test")
            val assistant = assistant { script }
            assistant.prompt = "Change the coin"
            assistant.ask()
            advanceUntilIdle()
            assertIs<AgentOutcome.Question>(assistant.outcome)

            editor.loadProject(Project.blank("Other game"))
            assistant.answer("Coin")
            advanceUntilIdle()

            assertNull(assistant.outcome)
            assertFalse(assistant.running)
            assertEquals("Your game changed, so the question was dropped. Nothing was applied.", assistant.notice)
        }

    @Test
    fun failingModelExplainsAndKeepsTheKey() =
        runTest {
            secrets.write(AssistantState.KEY_NAME, "sk-test")
            val failing =
                object : AgentModel {
                    override val info = ModelInfo("Broken", "test", live = true, label = "Live: broken (test)")

                    override suspend fun reply(request: ModelRequest): ModelReply = error("401 invalid key")
                }
            val assistant = assistant { failing }
            val before = editor.project
            assistant.prompt = "Anything"

            assistant.ask()
            advanceUntilIdle()

            assertEquals("The assistant could not answer: 401 invalid key", assistant.notice)
            assertEquals(before, editor.project)
            assertTrue(assistant.hasKey)
        }

    @Test
    fun staleAcceptIsRefusedPlainly() =
        runTest {
            val assistant = assistant()
            assistant.prompt = DemoModel.PROMPTS[0]
            assistant.ask()
            advanceUntilIdle()
            val coin = editor.scene.objects.first { it.name == "Coin" }

            editor.apply(EditOperation.RenameObject(editor.sceneId, coin.id, "Gold"))
            assistant.accept()

            assertTrue(assistant.notice.orEmpty().isNotBlank())
            assertEquals(
                emptyList(),
                editor.scene.objects
                    .first { it.name == "Gold" }
                    .rules,
            )
        }

    @Test
    fun keyIsStoredOnlyInTheSecretStore() =
        runTest {
            val received = mutableListOf<String>()
            val assistant =
                assistant { key ->
                    received += key
                    Scripted(
                        listOf(call(AgentToolbox.FINISH, """{"summary":"Done."}""")),
                        info = ModelInfo("test-model", "Test", live = true, label = "Live: test-model (Test)"),
                    )
                }
            assertFalse(assistant.hasKey)
            assertFalse(assistant.useLive)

            assistant.saveKey("sk-secret-999")
            assistant.prompt = "Hello"
            assistant.ask()
            advanceUntilIdle()

            assertEquals("sk-secret-999", secrets.read(AssistantState.KEY_NAME))
            assertEquals(listOf("sk-secret-999"), received, "Only the live model factory receives the key")
            assertTrue(assistant.useLive)
            val shown =
                listOf(assistant.modelLabel, assistant.notice.orEmpty(), assistant.costText) + assistant.progress +
                    assistant.transcript.map { it.toString() }
            assertTrue(shown.none { "sk-secret-999" in it }, shown.toString())
            assistant.removeKey()
            assertNull(secrets.read(AssistantState.KEY_NAME))
            assertFalse(assistant.useLive)
        }

    @Test
    fun signedInTheHostedModelAnswers() =
        runTest {
            val hosted = Scripted(listOf(call(AgentToolbox.FINISH, """{"summary":"Done."}""")), info = HOSTED_INFO)
            val assistant = assistant(hosted = { HostedChoice("server:1", hosted) })
            assistant.prompt = "Make coins worth 5"

            assistant.ask()
            advanceUntilIdle()

            assertEquals(1, hosted.requests.size)
            assertEquals(HOSTED_INFO.label, assistant.modelLabel)
            assertFalse(assistant.isDemo)
            assertFalse(assistant.needsSignIn)
        }

    @Test
    fun signedOutTheDemoAnswersWithASignInHint() =
        runTest {
            val assistant = assistant()

            assertTrue(assistant.isDemo)
            assertTrue(assistant.needsSignIn)
            assertEquals(DemoModel.LABEL, assistant.modelLabel)
        }

    @Test
    fun anotherAccountStartsANewConversation() =
        runTest {
            val first = Scripted(listOf(call(AgentToolbox.FINISH, """{"summary":"One."}""")), info = HOSTED_INFO)
            val second = Scripted(listOf(call(AgentToolbox.FINISH, """{"summary":"Two."}""")), info = HOSTED_INFO)
            var choice = HostedChoice("server:1", first)
            val assistant = assistant(hosted = { choice })
            assistant.prompt = "Hello"
            assistant.ask()
            advanceUntilIdle()

            choice = HostedChoice("server:2", second)
            assistant.prompt = "Hello again"
            assistant.ask()
            advanceUntilIdle()

            assertEquals(1, first.requests.size)
            assertEquals(1, second.requests.size)
            assertEquals(1, second.requests.single().messages.size, "the new account's conversation starts fresh")
        }

    @Test
    fun aDeveloperKeyWinsOnlyWhenSwitchedOn() =
        runTest {
            val hosted = Scripted(listOf(call(AgentToolbox.FINISH, """{"summary":"Hosted."}""")), info = HOSTED_INFO)
            val own = Scripted(listOf(call(AgentToolbox.FINISH, """{"summary":"Own key."}""")), info = ModelInfo("own", "Anthropic", live = true, label = "Live: own key"))
            val assistant = assistant(hosted = { HostedChoice("server:1", hosted) }) { own }
            assertEquals(HOSTED_INFO.label, assistant.modelLabel)

            assistant.saveKey("sk-test")

            assertEquals("Live: own key", assistant.modelLabel)
            assistant.useLive = false
            assertEquals(HOSTED_INFO.label, assistant.modelLabel)
        }

    @Test
    fun hostedRefusalsAreShownPlainly() =
        runTest {
            val refusing =
                object : AgentModel {
                    override val info = HOSTED_INFO

                    override suspend fun reply(request: ModelRequest): ModelReply =
                        throw HostedModelException("You have used today's free AI allowance. It renews at midnight UTC.")
                }
            val assistant = assistant(hosted = { HostedChoice("server:1", refusing) })
            assistant.prompt = "Make coins worth 5"

            assistant.ask()
            advanceUntilIdle()

            assertTrue("today's free AI allowance" in assistant.notice.orEmpty(), assistant.notice.toString())
            assertEquals(emptyList(), coinRules())
        }

    private companion object {
        val HOSTED_INFO = ModelInfo("SpawnKit AI", "SpawnKit", live = true, label = "Live: SpawnKit AI (free, with limits)")
    }
}
