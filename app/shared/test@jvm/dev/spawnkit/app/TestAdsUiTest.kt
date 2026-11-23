package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.ai.AdDayCount
import dev.spawnkit.ai.AdStatsReport
import dev.spawnkit.ai.ArcadeGame
import dev.spawnkit.ai.PurchaseCount
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.account.FakeAccountService
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.AdSettings
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Test ads in the builder (C12a): turned on in the Release Manager, shown in Play, and counted only on the Arcade. */
@OptIn(ExperimentalTestApi::class)
class TestAdsUiTest {
    private val server = FakeAccountService()
    private val secrets =
        MemorySecretStore().apply {
            write(AccountState.SERVER_NAME, "https://arcade.example.org")
            write(AccountState.TOKEN_NAME, FakeAccountService.TOKEN)
        }

    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val file =
            java.io
                .File("build/screenshots")
                .apply { mkdirs() }
                .resolve(name)
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun aPhoneCreatorTurnsOnTestAdsAndReadsTheirStatistics() = check(compact = true)

    @Test
    fun aDesktopCreatorTurnsOnTestAdsAndReadsTheirStatistics() = check(compact = false)

    private fun check(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            server.published += ArcadeGame("coin-dash-1", "Coin Dash", "https://arcade.example.org/play/coin-dash-1/")
            server.stats =
                AdStatsReport(
                    views = 120,
                    clicks = 6,
                    days = listOf(AdDayCount("2026-11-23", 80, 4), AdDayCount("2026-11-22", 40, 2)),
                    purchases = 3,
                    purchaseDays = listOf(PurchaseCount("2026-11-23", "100 coins", 3)),
                )
            val session = ProjectSession(TestProjectFiles())
            setContent {
                MaterialTheme(colorScheme = SpawnKitColors) {
                    EditorScreen(session.state, projectSession = session, secrets = secrets, accountService = { server })
                }
            }
            waitForIdle()
            tap("project-menu")
            tap("project-release")
            tap("ads-banner")
            tap("ads-interstitial")
            tap("ads-every-inc")
            assertEquals(AdSettings(banner = true, interstitial = true, every = 4), session.state.project.ads)

            tap("my-game-stats-coin-dash-1")
            onNodeWithTag("ad-revenue").assertTextContains("$0.00 — test ads earn nothing", substring = true)
            onNodeWithText("Views 120 · Clicks 6 · Click-through rate 5.0%", substring = true).assertExists()
            onNodeWithTag("purchase-total").assertTextContains("Test purchases 3 — no money is charged", substring = true)
            onNodeWithText("2026-11-23: 3 × 100 coins").assertExists()
            onNodeWithTag("what-if-rate").performTextReplacement("2.5")
            waitForIdle()
            onNodeWithTag("what-if-estimate").assertTextContains("About $0.30 — an estimate, not earnings", substring = true)
            screenshot(if (compact) "ad-stats-phone.png" else "ad-stats-desktop.png")
            tap("ad-stats-close")
            tap("release-back")

            // Builder Play shows the labelled test banner on the start scene; previews have no reporter, so nothing counts.
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("ad-banner").assertIsDisplayed()
            screenshot(if (compact) "ad-banner-phone.png" else "ad-banner-desktop.png")
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
        }
}
