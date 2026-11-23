package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.account.FakeAccountService
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Account screen (C11b) at phone and desktop sizes, against a fake SpawnKit server. */
@OptIn(ExperimentalTestApi::class)
class AccountUiTest {
    private val secrets = MemorySecretStore()
    private val server = FakeAccountService()

    private fun ComposeUiTest.openAccount() {
        val session = ProjectSession(TestProjectFiles())
        setContent {
            MaterialTheme(colorScheme = SpawnKitColors) {
                EditorScreen(session.state, projectSession = session, secrets = secrets, accountService = { server })
            }
        }
        onNodeWithTag("project-menu").performClick()
        onNodeWithTag("project-account").performClick()
        onNodeWithTag("account-screen").assertIsDisplayed()
    }

    private fun ComposeUiTest.type(
        tag: String,
        text: String,
    ) {
        onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    /** Dialog buttons are not inside the screen's scrolling column. */
    private fun ComposeUiTest.confirm(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun aPhoneCreatesAnAccountWithItsCodeAndSignsOut() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            openAccount()
            type("account-address", "https://arcade.example.org")
            tap("account-tab-create")
            type("account-name", "Ada")
            type("account-email", "ada@example.com")
            type("account-password", "correct horse")
            tap("account-create")

            onNodeWithText("Check your email for a 6-digit code to finish.").assertIsDisplayed()
            type("account-code", "123456")
            tap("account-verify")

            onNodeWithText("Signed in as Ada", substring = true).assertIsDisplayed()
            assertEquals(FakeAccountService.TOKEN, secrets.read(AccountState.TOKEN_NAME))
            onNodeWithText("SpawnKit AI is available.").assertIsDisplayed()

            tap("account-sign-out")
            onNodeWithTag("account-tab-sign-in").assertIsDisplayed()
            assertNull(secrets.read(AccountState.TOKEN_NAME))
        }

    @Test
    fun aDesktopSignsInAndDeletesTheAccountAfterConfirming() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            openAccount()
            type("account-address", "https://arcade.example.org")
            type("account-email", "ada@example.com")
            type("account-password", "correct horse")
            tap("account-sign-in")
            onNodeWithText("Signed in as Ada", substring = true).assertIsDisplayed()

            tap("account-delete")
            confirm("account-delete-no")
            onNodeWithText("Signed in as Ada", substring = true).assertIsDisplayed()
            assertEquals(0, server.deleted, "nothing happens without confirming")

            tap("account-delete")
            confirm("account-delete-yes")

            onNodeWithText("Your account was deleted", substring = true).assertIsDisplayed()
            assertEquals(1, server.deleted)
        }

    @Test
    fun anInsecureAddressIsExplained() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            openAccount()
            type("account-address", "http://arcade.example.org")
            tap("account-check")

            onNodeWithText("HTTPS address", substring = true).assertIsDisplayed()
        }

    @Test
    fun forgottenPasswordsAreResetWithACode() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            openAccount()
            type("account-address", "https://arcade.example.org")
            tap("account-tab-reset")
            type("account-email", "ada@example.com")
            tap("account-send-code")
            type("account-code", "123456")
            type("account-new-password", "brand new pass")
            tap("account-set-password")

            onNodeWithText("Signed in as Ada", substring = true).assertIsDisplayed()
        }
}
