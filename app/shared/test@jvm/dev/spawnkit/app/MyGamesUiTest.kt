package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.account.FakeAccountService
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Publishing as the signed-in account, with My games, in the Release Manager (C11b). */
@OptIn(ExperimentalTestApi::class)
class MyGamesUiTest {
    private val server = FakeAccountService()
    private val secrets =
        MemorySecretStore().apply {
            write(AccountState.SERVER_NAME, "https://arcade.example.org")
            write(AccountState.TOKEN_NAME, FakeAccountService.TOKEN)
        }

    private fun ComposeUiTest.openRelease() {
        val session = ProjectSession(TestProjectFiles())
        setContent {
            MaterialTheme(colorScheme = SpawnKitColors) {
                EditorScreen(session.state, projectSession = session, secrets = secrets, accountService = { server })
            }
        }
        waitForIdle()
        onNodeWithTag("add:Score").performClick()
        onNodeWithTag("project-menu").performClick()
        onNodeWithTag("project-release").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.confirm(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.publishAndUpdateTheChosenGame() {
        onNodeWithText("Publishing as Ada", substring = true).performScrollTo().assertIsDisplayed()
        tap("release-publish")
        confirm("publish-confirm-yes")
        tap("release-publish")
        confirm("publish-confirm-yes")
        assertEquals(2, server.published.size)
        onNodeWithTag("release-link").performScrollTo().assertIsDisplayed()
        assertEquals(2, onAllNodesWithTag("my-game", useUnmergedTree = true).fetchSemanticsNodes().size)

        val second = server.published[1].slug
        tap("my-game-update-$second")
        confirm("update-confirm-yes")
        assertEquals(listOf(second), server.republished, "only the game the creator picked is replaced")

        tap("my-game-unpublish-$second")
        confirm("unpublish-confirm-yes")
        assertEquals(1, server.published.size)
        assertTrue(server.published.none { it.slug == second })
    }

    @Test
    fun aPhonePublishesAsTheOwnerAndManagesMyGames() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            openRelease()
            publishAndUpdateTheChosenGame()
        }

    @Test
    fun aDesktopPublishesAsTheOwnerAndManagesMyGames() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            openRelease()
            publishAndUpdateTheChosenGame()
        }
}
