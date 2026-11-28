package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import dev.spawnkit.ai.WholeGameReplay
import dev.spawnkit.app.assistant.AssistantState
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.BuildRequest
import dev.spawnkit.app.persistence.ProjectAction
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI-first building (C13b): New game starts with "Describe your game…", the assistant builds on a new blank game and its
 * changes are applied right away with Revert, in both layouts. No live model is called.
 */
@OptIn(ExperimentalTestApi::class)
class AiFirstUiTest {
    /** Stands in for SpawnKit AI: adds a level and a win scene through the builder's tools, then finishes. */
    private class Builder : AgentModel {
        val prompts = mutableListOf<String>()
        private var step = 0
        override val info = ModelInfo("test-model", "Test", live = true, label = "Live: test-model (Test)")

        override suspend fun reply(request: ModelRequest): ModelReply {
            if (step == 0) prompts += request.messages.toString()
            val calls =
                when (step++) {
                    0 -> listOf(call("add_scene", """{"name":"Level 1"}"""), call("add_scene", """{"name":"You win"}"""))
                    else -> listOf(call(AgentToolbox.FINISH, """{"summary":"Built a level and a win scene."}"""))
                }
            return ModelReply(null, calls)
        }

        private fun call(
            name: String,
            json: String,
        ) = ToolCall("t-$name-$step", name, Json.parseToJsonElement(json).jsonObject)
    }

    private fun ComposeUiTest.open(
        session: ProjectSession,
        model: AgentModel? = null,
    ) {
        // A developer key stands in for a signed-in account, so a model can build; without it the demo answers.
        val secrets = MemorySecretStore().apply { if (model != null) write(AssistantState.KEY_NAME, "sk-test") }
        setContent {
            MaterialTheme(colorScheme = SpawnKitColors) {
                EditorScreen(session.state, projectSession = session, secrets = secrets, liveModel = { model ?: error("no model") })
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.waitForText(
        text: String,
        timeout: Long = 10_000,
    ) = waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.capture(name: String) {
        val destination = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        // Dialogs add a second semantics root to the same offscreen scene; the first root captures everything.
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", destination)
    }

    @Test
    fun aPhoneCreatorDescribesAGameAndTheAssistantBuildsIt() = describe(compact = true)

    @Test
    fun aDesktopCreatorDescribesAGameAndTheAssistantBuildsIt() = describe(compact = false)

    private fun describe(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            val session = ProjectSession(TestProjectFiles())
            session.state.add(DefaultPresets.first { it.name == "Box" })
            session.save()
            val model = Builder()
            open(session, model)
            val suffix = if (compact) "phone" else "desktop"

            tap("project-menu")
            tap("project-new")
            onNodeWithTag("new-build").assertIsNotEnabled()
            onNodeWithTag("new-describe").performTextReplacement("A jungle game with a level and a win screen")
            capture("new-game-describe-$suffix")
            tap("new-build")
            waitForText("Applied to your game")

            assertTrue("A jungle game with a level and a win screen" in model.prompts.single(), model.prompts.single())
            assertEquals(
                listOf("Main scene", "Level 1", "You win"),
                session.state.project.scenes
                    .map { it.name },
            )
            assertTrue(
                session.state.scene.objects
                    .isEmpty(),
                "the assistant built on a new blank game",
            )
            onNodeWithTag("chat-request-0").assertTextContains("A jungle game with a level and a win screen")
            onNodeWithTag("chat-applied-1").assertIsDisplayed()
            capture("ai-first-built-$suffix")

            tap("chat-revert-1")
            assertEquals(
                listOf("Main scene"),
                session.state.project.scenes
                    .map { it.name },
            )
            assertTrue(onAllNodesWithText("↶ Reverted").fetchSemanticsNodes().isNotEmpty(), "the card says it was reverted")
            tap("redo")
            assertEquals(3, session.state.project.scenes.size, "Revert is one ordinary undo step")
            onNodeWithTag("chat-revert-1").assertIsEnabled()
            assertTrue(onAllNodesWithText("✓ Applied to your game").fetchSemanticsNodes().isNotEmpty(), "Redo brings it back")
        }

    @Test
    fun signedOutNewGameOffersSignInAndAPhoneWatchesTheLabelledReplay() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val session = ProjectSession(TestProjectFiles())
            open(session)

            tap("project-menu")
            tap("project-new")
            assertTrue(onAllNodesWithTag("new-build").fetchSemanticsNodes().isEmpty(), "building with AI needs an account")
            onNodeWithTag("new-sign-in-hint").assertIsDisplayed()
            onNodeWithTag("new-sign-in").assertIsDisplayed()
            capture("new-game-signed-out-phone")
            tap("new-watch-replay")
            waitForText("Replay: claude-sonnet-5-5")
            // The canvas shows the staged game as it grows, labelled as a preview.
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("assistant-preview").fetchSemanticsNodes().isNotEmpty() }
            capture("ai-first-replay-running-phone")
            waitForText("Applied to your game", timeout = 60_000)

            assertEquals(
                listOf("Title", "Level 1", "Level 2", "Shop", "Jungle Champion", "Game Over"),
                session.state.project.scenes
                    .map { it.name },
            )
            assertTrue(onAllNodesWithText("Replay of a recorded session", substring = true).fetchSemanticsNodes().isNotEmpty())
            onNodeWithTag("assistant-cost").assertTextContains("No cost (no model is called)")
            onNodeWithTag("assistant-model").assertTextContains(DemoModel.LABEL)
            assertTrue(onAllNodesWithTag("assistant-preview").fetchSemanticsNodes().isEmpty(), "applied, so no longer a preview")
            capture("ai-first-replay-phone")
        }

    @Test
    fun describingWithUnsavedChangesAsksFirstAndCancelBuildsNothing() {
        val session = ProjectSession(TestProjectFiles())
        session.state.add(DefaultPresets.first { it.name == "Box" })
        val game = session.state.project

        session.describe(BuildRequest("A racing game"))
        assertEquals(ProjectAction.New, session.pending)
        session.cancelNavigation()

        assertEquals(game, session.state.project)
        assertNull(session.buildRequest)
        session.startNew(null)
        session.discardAndContinue()
        assertNull(session.buildRequest, "a cancelled description is not built later")

        session.state.add(DefaultPresets.first { it.name == "Box" })
        session.describe(BuildRequest("A racing game"))
        session.discardAndContinue()
        assertEquals(Project.blank(), session.state.project)
        assertEquals("A racing game", session.takeBuildRequest()?.prompt)
        assertNull(session.takeBuildRequest(), "a request is handled once")
    }

    @Test
    fun theReplayIsOfferedWithItsRecordedPrompt() {
        val session = ProjectSession(TestProjectFiles())
        session.describe(BuildRequest(WholeGameReplay.prompt, replay = true))

        val request = session.takeBuildRequest()
        assertTrue(request?.replay == true)
        assertTrue("jungle platformer" in request.prompt)
    }
}
