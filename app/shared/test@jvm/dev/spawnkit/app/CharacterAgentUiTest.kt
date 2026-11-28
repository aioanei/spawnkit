package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.ai.CharacterResult
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.ForgedImage
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.account.FakeAccountService
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.app.images.ImagePicker
import dev.spawnkit.app.images.LocalImagePicker
import dev.spawnkit.app.images.PickedImage
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.ProjectImages
import dev.spawnkit.gameplay.RuleItem
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Character Forge from the assistant chat (C16b, ADR-0009): a photo attached with "make this person a character" asks
 * before anything is sent, and the result is the same Character the builder's dialog makes. The scripted demo stands
 * in for the text model and a fake server for the image model; neither is live.
 */
@OptIn(ExperimentalTestApi::class)
class CharacterAgentUiTest {
    private val server = FakeAccountService().apply { aiAvailable = false }
    private val secrets =
        MemorySecretStore().apply {
            write(AccountState.SERVER_NAME, "https://arcade.example.org")
            write(AccountState.TOKEN_NAME, FakeAccountService.TOKEN)
        }

    private fun png(color: Int): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A photo with an author's name in a text chunk, as cameras and editors write. */
    private fun photoWithMetadata(): ByteArray {
        val plain = png(0xFF8D6E63.toInt())
        val text = "tEXt".encodeToByteArray() + "Author\u0000Ada Lovelace".encodeToByteArray()
        val chunk = byteArrayOf(0, 0, 0, (text.size - 4).toByte()) + text + byteArrayOf(0, 0, 0, 0)
        return plain.copyOfRange(0, 33) + chunk + plain.copyOfRange(33, plain.size)
    }

    private val forged =
        CharacterResult(
            listOf(
                ForgedImage("pixel", "Pixel art", ProjectImages.encode(png(0xFF1E88E5.toInt())), "Fake image-1, 2026-11-23"),
                ForgedImage("cartoon", "Cartoon", ProjectImages.encode(png(0xFFE53935.toInt())), "Fake image-1, 2026-11-23"),
            ),
            emptyMap(),
        )

    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.waitForTag(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.screenshot(name: String) {
        val file = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun ComposeUiTest.open(
        state: EditorState,
        session: ProjectSession,
        compact: Boolean,
    ) {
        setContent {
            CompositionLocalProvider(
                LocalImagePicker provides ImagePicker { it(Result.success(PickedImage("me", photoWithMetadata()))) },
            ) {
                MaterialTheme(colorScheme = SpawnKitColors) {
                    EditorScreen(state, projectSession = session, secrets = secrets, accountService = { server })
                }
            }
        }
        waitForIdle()
        tap(if (compact) "tab:AI" else "panel:Assistant")
    }

    @Test
    fun aPhoneCreatorMakesACharacterFromTheChat() = check(compact = true)

    @Test
    fun aDesktopCreatorMakesACharacterFromTheChat() = check(compact = false)

    private fun check(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            server.forged = forged
            val session = ProjectSession(TestProjectFiles())
            val state = session.state
            open(state, session, compact)
            onNodeWithTag("assistant-model").assertTextContains(DemoModel.LABEL)

            tap("assistant-attach")
            onNodeWithTag("assistant-photo").assertTextContains("Photo attached: me")
            tap("assistant-example-3")
            tap("assistant-ask")
            waitForTag("assistant-confirm")
            assertNull(server.lastPhoto, "nothing is sent before the creator confirms")
            onNodeWithTag("assistant-send-photo").assertIsNotEnabled()
            tap("assistant-consent")
            screenshot(if (compact) "character-agent-confirm-compact" else "character-agent-confirm-expanded")
            tap("assistant-send-photo")
            // Request, the note that the photo was sent, then the changes, applied right away (C13b).
            waitForTag("chat-applied-2")

            val sent = ProjectImages.decode(assertNotNull(server.lastPhoto))
            assertTrue(!sent.decodeToString().contains("Ada Lovelace"), "metadata never leaves the device")
            assertTrue(onAllNodesWithTag("assistant-photo").fetchSemanticsNodes().isEmpty(), "the photo is let go once sent")
            onNodeWithTag("assistant-cost").assertTextContains("2 character images from your SpawnKit AI allowance", substring = true)
            screenshot(if (compact) "character-agent-applied-compact" else "character-agent-applied-expanded")

            assertEquals(listOf("Pixel art character", "Cartoon character"), state.project.images.map { it.name })
            val character = assertNotNull(state.scene.objects.singleOrNull { it.name == "Character" })
            assertEquals(true, character.component("graphics:sprite")?.values?.get("pixelated"))
            assertEquals(3, state.ruleItems(character.id).filterIsInstance<RuleItem.Blocks>().size)

            // Parity (ADR-0009): the builder's "Character from photo" dialog makes exactly the same game.
            val byHand = ProjectSession(TestProjectFiles()).state
            byHand.addCharacterImages(forged.images, "pixel")
            assertEquals(byHand.project.copy(name = state.project.name), state.project)

            state.undo()
            assertEquals(emptyList(), state.project.images, "one undo removes the character and its images")
        }

    @Test
    fun declinedPhotosAreNeverSent() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            server.forged = forged
            val session = ProjectSession(TestProjectFiles())
            open(session.state, session, compact = true)
            tap("assistant-attach")
            tap("assistant-example-3")
            tap("assistant-ask")
            waitForTag("assistant-confirm")
            tap("assistant-dont-send")
            waitForTag("chat-reply-2")

            onNodeWithTag("chat-reply-2").assertTextContains("nothing was sent", substring = true)
            assertNull(server.lastPhoto)
            assertEquals(emptyList(), session.state.project.images)
        }
}
