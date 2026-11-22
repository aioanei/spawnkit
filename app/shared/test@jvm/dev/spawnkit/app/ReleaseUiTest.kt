package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.app.release.ReleasePlatform
import dev.spawnkit.editing.GamePackageCodec
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReleaseUiTest {
    private class RecordingPlatform : ReleasePlatform {
        override val available = true
        var export: String? = null
        var address: String? = null
        var publication = CompletableDeferred<String>()

        override suspend fun exportWeb(
            suggestedName: String,
            packageJson: String,
        ): Boolean {
            export = packageJson
            return true
        }

        override suspend fun publish(
            arcadeUrl: String,
            packageJson: String,
        ): String {
            address = arcadeUrl
            return publication.await()
        }
    }

    private fun ComposeUiTest.open(
        platform: RecordingPlatform,
        fontScale: Float = 1f,
        onOpenLink: (String) -> Unit = {},
    ): EditorState {
        val session = ProjectSession(TestProjectFiles())
        val browser =
            object : UriHandler {
                override fun openUri(uri: String) = onOpenLink(uri)
            }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalUriHandler provides browser) {
                MaterialTheme(colorScheme = SpawnKitColors) {
                    EditorScreen(session.state, projectSession = session, releasePlatform = platform)
                }
            }
        }
        onNodeWithTag("add:Score").performClick()
        onNodeWithTag("project-menu").performClick()
        onNodeWithTag("project-release").performClick()
        return session.state
    }

    private fun ComposeUiTest.expectMessage(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(onNodeWithTag("release-manager").captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun phoneCreatorCorrectsTheTitleAndExportsTheirCurrentGame() =
        runDesktopComposeUiTest(width = 360, height = 915) {
            val platform = RecordingPlatform()
            val state = open(platform, fontScale = 1.3f)
            val web = onNodeWithText("Web export").fetchSemanticsNode().boundsInRoot
            val arcade = onNodeWithText("SpawnKit Arcade").fetchSemanticsNode().boundsInRoot
            assertTrue(web.bottom < arcade.top)
            capture("release-manager-compact")
            onNodeWithTag("release-title").performTextReplacement("")
            onNodeWithTag("release-export").performScrollTo().performClick()
            expectMessage("Give the game a title")
            assertEquals(null, platform.export)
            onNodeWithTag("release-title").performScrollTo().performTextReplacement("Nine stars")
            onNodeWithTag("release-export").performScrollTo().performClick()
            expectMessage("Web game saved.")
            val pkg = GamePackageCodec.decode(checkNotNull(platform.export))
            assertEquals("Nine stars", pkg.title)
            assertEquals(state.editor.saveSnapshot(), pkg.project)
            onNodeWithText("These destinations require", substring = true).performScrollTo()
            onNodeWithTag("release-back").performClick()
            onNodeWithTag("undo").performClick()
            assertTrue(state.scene.objects.isEmpty())
        }

    @Test
    fun desktopCreatorCanCancelPublishingAndThenOpenAPlayLink() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val platform = RecordingPlatform()
            var opened: String? = null
            open(platform) { opened = it }
            val web = onNodeWithText("Web export").fetchSemanticsNode().boundsInRoot
            val arcade = onNodeWithText("SpawnKit Arcade").fetchSemanticsNode().boundsInRoot
            assertTrue(web.right < arcade.left)
            capture("release-manager-expanded")
            onNodeWithTag("release-publish").assertIsNotEnabled()
            val address = "https://arcade.example.org"
            onNodeWithTag("release-address").performTextReplacement(address)
            onNodeWithTag("release-publish").performClick()
            expectMessage("Uploading your game")
            onNodeWithTag("release-back").assertIsNotEnabled()
            onNodeWithTag("release-cancel").performClick()
            expectMessage("Publishing cancelled")
            assertFalse(platform.publication.isCompleted)
            assertEquals(address, platform.address)
            val url = "$address/play/nine-stars/"
            platform.publication.complete(url)
            onNodeWithTag("release-publish").performClick()
            expectMessage(url)
            onNodeWithText("Open game").performScrollTo().performClick()
            waitForIdle()
            assertEquals(url, opened)
            onNodeWithText("Copy link").assertExists()
        }
}
