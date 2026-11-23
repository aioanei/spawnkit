package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.ai.CharacterResult
import dev.spawnkit.ai.ForgedImage
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.account.FakeAccountService
import dev.spawnkit.app.assistant.MemorySecretStore
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.images.ImagePicker
import dev.spawnkit.app.images.LocalImagePicker
import dev.spawnkit.app.images.PickedImage
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.ProjectImages
import dev.spawnkit.gameplay.RuleItem
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Character Forge in the builder (C16a), with a fake server: from photo to a playable Character in both layouts. */
@OptIn(ExperimentalTestApi::class)
class CharacterForgeUiTest {
    private val server = FakeAccountService()
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

    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    @Test
    fun aPhoneCreatorMakesACharacterFromAPhoto() = check(compact = true)

    @Test
    fun aDesktopCreatorMakesACharacterFromAPhoto() = check(compact = false)

    private fun check(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            server.forged =
                CharacterResult(
                    listOf(
                        ForgedImage("pixel", "Pixel art", ProjectImages.encode(png(0xFF1E88E5.toInt())), "Fake image-1, 2026-11-23"),
                        ForgedImage("cartoon", "Cartoon", ProjectImages.encode(png(0xFFE53935.toInt())), "Fake image-1, 2026-11-23"),
                    ),
                    mapOf("chibi" to "The image model declined this photo. Try a different photo."),
                )
            val session = ProjectSession(TestProjectFiles())
            val state = session.state
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
            tap("add:Character")
            tap("forge-photo")
            onNodeWithTag("forge-make").assertIsNotEnabled()
            tap("forge-consent")
            tap("forge-style:cartoon")
            tap("forge-style:chibi")
            tap("forge-make")
            waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("forge-use:pixel").fetchSemanticsNode() }.isSuccess }

            val sent = ProjectImages.decode(assertNotNull(server.lastPhoto))
            assertTrue(!sent.decodeToString().contains("Ada Lovelace"), "metadata never leaves the device")
            tap("forge-use:pixel")

            assertEquals(listOf("Pixel art character", "Cartoon character"), state.project.images.map { it.name })
            assertTrue(state.project.images.all { it.provenance == "Fake image-1, 2026-11-23" })
            val character = assertNotNull(state.scene.objects.singleOrNull { it.name == "Character" })
            val sprite = assertNotNull(character.component("graphics:sprite")).values
            assertEquals(
                state.project.images
                    .first()
                    .id,
                sprite["asset"],
            )
            assertEquals(true, sprite["pixelated"])
            assertNotNull(character.component("gameplay:body"))
            assertEquals("player", character.component("gameplay:tag")?.values?.get("tag"))
            assertEquals(3, state.ruleItems(character.id).filterIsInstance<RuleItem.Blocks>().size)
            assertEquals("Add character", state.editor.undoLabel)
            state.undo()
            assertEquals(emptyList(), state.project.images, "one undo removes the character and its images")
        }
}
