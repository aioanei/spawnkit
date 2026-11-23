package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.app.images.ImagePicker
import dev.spawnkit.app.images.LocalImagePicker
import dev.spawnkit.app.images.PickedImage
import dev.spawnkit.app.images.fitPng
import dev.spawnkit.editing.ProjectCodec
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Images in the builder (C15b), in both layouts: the Image piece imports a picture and places it, the inspector picks a
 * sprite's image and Pixelated, an image in use cannot be removed, and Play draws the picture.
 */
@OptIn(ExperimentalTestApi::class)
class ImagesUiTest {
    private fun picture(
        width: Int,
        height: Int,
        format: String = "png",
        color: Int = 0xFFE91E63.toInt(),
    ): ByteArray {
        val image = BufferedImage(width, height, if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    @Test
    fun largePhotosAreConvertedToPngsThatFit() {
        val photo = picture(3000, 1500, format = "jpg")
        val png = fitPng(photo)
        val info =
            dev.spawnkit.editing.ProjectImages
                .check(png)
        assertEquals(1024, info.width)
        assertEquals(512, info.height)
    }

    @Test
    fun aPhoneCreatorPutsALogoOnTheirGame() = checkImages(compact = true)

    @Test
    fun aDesktopCreatorPutsALogoOnTheirGame() = checkImages(compact = false)

    private fun checkImages(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            val state = EditorState()
            var next = PickedImage("Logo", picture(40, 20))
            setContent {
                CompositionLocalProvider(LocalImagePicker provides ImagePicker { it(Result.success(next)) }) {
                    MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) }
                }
            }
            tap("add:Image")
            val logo = state.project.images.single()
            val obj = assertNotNull(state.selection?.let(state.scene::find))
            assertEquals("Logo", obj.name)
            val sprite = assertNotNull(obj.component("graphics:sprite")).values
            assertEquals(logo.id, sprite["asset"])
            assertEquals(3.0, sprite["width"])
            assertEquals(1.5, sprite["height"], "the picture keeps its shape")
            assertEquals("Add image Logo", state.editor.undoLabel)

            next = PickedImage("Badge", picture(8, 8, color = 0xFF2196F3.toInt()))
            tap("image-import")
            val badge = state.project.images.last()
            assertEquals(listOf("Logo", "Badge"), state.project.images.map { it.name })

            tap(if (compact) "tab:Object" else "panel:Properties")
            tap("image:${obj.id}/graphics:sprite/asset:Badge")
            tap("field:${obj.id}/graphics:sprite/pixelated")
            val changed = assertNotNull(state.scene.find(obj.id)?.component("graphics:sprite")).values
            assertEquals(badge.id, changed["asset"])
            assertEquals(true, changed["pixelated"])

            if (compact) tap("tab:Add")
            tap("image-remove:Badge")
            assertTrue(state.message.orEmpty().contains("Badge is used by Logo"), "an image in use stays: ${state.message}")
            tap("image-remove:Logo")
            assertEquals(listOf("Badge"), state.project.images.map { it.name }, "an unused image can be removed")
            assertEquals(state.project, ProjectCodec.decode(ProjectCodec.encode(state.project)), "the picture is saved with the game")

            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(500)
            val shot = onRoot().captureToImage().toAwtImage()
            var blue = 0
            for (x in 0 until shot.width step 2) {
                for (y in 0 until shot.height step 2) {
                    val c = shot.getRGB(x, y)
                    if (c == 0xFF2196F3.toInt()) blue++
                }
            }
            assertTrue(blue > 100, "Play draws the badge picture ($blue blue pixels)")
            val file =
                java.io
                    .File("build/screenshots")
                    .apply { mkdirs() }
                    .resolve(if (compact) "images-play-phone.png" else "images-play-desktop.png")
            ImageIO.write(shot, "png", file)
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
        }
}
