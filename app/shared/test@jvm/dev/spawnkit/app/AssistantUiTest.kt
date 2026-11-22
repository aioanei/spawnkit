package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.AgentToolbox
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.ModelInfo
import dev.spawnkit.ai.ModelReply
import dev.spawnkit.ai.ModelRequest
import dev.spawnkit.ai.ToolCall
import dev.spawnkit.app.assistant.AssistantState
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.assistant.SecretStore
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The assistant in the real builder at phone and desktop sizes (C7c). No live model is called. */
@OptIn(ExperimentalTestApi::class)
class AssistantUiTest {
    private fun collectGame() =
        EditorState().apply {
            listOf("Player" to 0.0, "Coin" to 3.0, "Score" to -4.0).forEach { (name, x) ->
                add(DefaultPresets.first { it.name == name }, x, 0.0)
            }
            selection = null
        }

    private fun EditorState.coinRules() = scene.objects.first { it.name == "Coin" }.rules

    private fun ComposeUiTest.open(
        state: EditorState,
        secrets: SecretStore = MemorySecretStore(),
        live: (String) -> AgentModel = { error("No live model in this test") },
    ) = setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state, secrets = secrets, liveModel = live) } }

    private fun ComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.screenshot(name: String) {
        val file = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private class Scripted(
        vararg replies: List<ToolCall>,
    ) : AgentModel {
        private val queue = ArrayDeque(replies.toList())
        override val info = ModelInfo("test-model", "Test", live = true, label = "Live: test-model (Test)")

        override suspend fun reply(request: ModelRequest) = ModelReply(null, queue.removeFirst())
    }

    private fun call(
        name: String,
        json: String,
    ) = ToolCall("t-$name", name, Json.parseToJsonElement(json).jsonObject)

    @Test
    fun phoneDemoBuildsCollectRuleAcceptsAndUndoes() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val state = collectGame()
            open(state)

            onNodeWithTag("tab:AI").performClick()
            onNodeWithTag("assistant-model").assertTextContains(DemoModel.LABEL)
            onNodeWithTag("assistant-example-0").performScrollTo().performClick()
            onNodeWithTag("assistant-ask").performScrollTo().performClick()
            waitForText("Add a rule to Coin")
            assertEquals(emptyList(), state.coinRules(), "Nothing changes before Accept")
            waitForIdle()
            screenshot("assistant-compact")
            onNodeWithTag("assistant-accept").performScrollTo().performClick()
            waitForIdle()

            assertEquals(1, state.coinRules().size)
            onNodeWithTag("undo").performClick()
            waitForIdle()
            assertEquals(emptyList(), state.coinRules(), "Accepting is one undo step")
        }

    @Test
    fun desktopRejectAndCancelKeepTheGame() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val state = collectGame()
            val before = state.project
            val gate = CompletableDeferred<ModelReply>()
            val slow =
                object : AgentModel {
                    override val info = ModelInfo("slow", "Test", live = true, label = "Live: slow (Test)")

                    override suspend fun reply(request: ModelRequest) = gate.await()
                }
            open(state, live = { slow })

            onNodeWithTag("panel:Assistant").performClick()
            onNodeWithTag("assistant-example-0").performScrollTo().performClick()
            onNodeWithTag("assistant-ask").performScrollTo().performClick()
            waitForText("Add a rule to Coin")
            screenshot("assistant-expanded")
            onNodeWithTag("assistant-reject").performScrollTo().performClick()
            waitForIdle()
            assertEquals(before, state.project)

            onNodeWithTag("assistant-settings").performClick()
            onNodeWithTag("assistant-key").performScrollTo().performTextReplacement("sk-test")
            onNodeWithTag("assistant-key-save").performScrollTo().performClick()
            onNodeWithTag("assistant-prompt").performScrollTo().performTextReplacement("Make coins worth 5")
            onNodeWithTag("assistant-ask").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("assistant-cancel").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("assistant-cancel").performScrollTo().performClick()
            waitForText("Cancelled. Nothing in your game changed.")

            assertEquals(before, state.project)
        }

    @Test
    fun clarifyingQuestionIsAnsweredWithAnOption() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val secrets = MemorySecretStore().apply { write(AssistantState.KEY_NAME, "sk-test") }
            val script =
                Scripted(
                    listOf(call(AgentToolbox.ASK, """{"question":"Which coin?","options":["Coin","All coins"]}""")),
                    listOf(call(AgentToolbox.FINISH, """{"summary":"Nothing to change."}""")),
                )
            open(collectGame(), secrets) { script }

            onNodeWithTag("panel:Assistant").performClick()
            onNodeWithTag("assistant-prompt").performScrollTo().performTextReplacement("Change the coin")
            onNodeWithTag("assistant-ask").performScrollTo().performClick()
            waitForText("Which coin?")
            onNodeWithTag("assistant-option-1").performScrollTo().performClick()

            waitForText("Nothing to change.")
        }

    @Test
    fun askAiFromRulesPrefillsThePrompt() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val state = collectGame()
            open(state)
            state.selection =
                state.scene.objects
                    .first { it.name == "Coin" }
                    .id

            onNodeWithTag("panel:Rules").performClick()
            onNodeWithTag("ask-ai").performScrollTo().performClick()
            waitForIdle()

            onNodeWithTag("assistant-prompt").assertTextContains("Help me with the rules of Coin.")
            onNodeWithTag("assistant-ask").assertExists()
        }

    @Test
    fun keyIsSavedMaskedAndNeverShown() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val secrets = MemorySecretStore()
            open(collectGame(), secrets) { Scripted() }

            onNodeWithTag("panel:Assistant").performClick()
            onNodeWithTag("assistant-settings").performClick()
            onNodeWithTag("assistant-key").performScrollTo().performTextReplacement("sk-secret-777")
            onNodeWithTag("assistant-key-save").performScrollTo().performClick()
            waitForIdle()

            assertEquals("sk-secret-777", secrets.read(AssistantState.KEY_NAME))
            assertTrue(onAllNodesWithText("sk-secret-777", substring = true).fetchSemanticsNodes().isEmpty(), "The key is never shown")
            onNodeWithTag("assistant-model").assertTextContains("Live: test-model (Test)")
            waitForText("kept until you close SpawnKit")
            onNodeWithTag("assistant-key-remove").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("assistant-model").assertTextContains(DemoModel.LABEL)
        }
}
