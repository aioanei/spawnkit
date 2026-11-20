package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the real builder UI at phone and desktop sizes. */
@OptIn(ExperimentalTestApi::class)
class BuilderUiTest {
    private fun SemanticsNodeInteraction.screenshot(name: String) {
        val file = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun phoneLayoutCreatesEditsUndoesAndPlaysAGame() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            // Add tab is open by default on phones.
            onNodeWithTag("add:Platform").performClick()
            onNodeWithTag("add:Player").performClick()
            waitForIdle()
            val scene = state.scene
            assertEquals(listOf("Platform", "Player"), scene.objects.map { it.name })
            val player = checkNotNull(state.selection)
            assertEquals("Player", scene.find(player)?.name)

            onNodeWithTag("tab:Properties").performClick()
            onNodeWithTag("swatch:$player/graphics:shape/color:fff44336").performScrollTo().performClick()
            waitForIdle()
            assertEquals(
                0xFFF44336,
                state.scene
                    .find(player)
                    ?.component("graphics:shape")
                    ?.values
                    ?.get("color"),
            )
            // The viewport must redraw after inspector edits: the player at the view centre is now red.
            val pixels = onNodeWithTag("viewport").captureToImage().toAwtImage().let { it as java.awt.image.BufferedImage }
            assertEquals(0xFFF44336.toInt(), pixels.getRGB(pixels.width / 2, pixels.height / 2))
            onNodeWithTag("inc:$player/graphics:transform/x").performScrollTo().performClick()
            waitForIdle()
            assertEquals(
                0.5,
                state.scene
                    .find(player)
                    ?.component("graphics:transform")
                    ?.values
                    ?.get("x"),
            )
            onRoot().screenshot("builder-phone")

            onNodeWithTag("undo").performClick()
            onNodeWithTag("undo").performClick()
            waitForIdle()
            assertEquals(
                0xFF2196F3,
                state.scene
                    .find(player)
                    ?.component("graphics:shape")
                    ?.values
                    ?.get("color"),
            )

            // Play runs a continuous frame loop, so drive the test clock by hand instead of waiting for idle.
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(500)
            val session = assertNotNull(state.play)
            assertTrue(session.frame > 0, "The play loop presents frames")
            assertTrue(session.scene.world.tick >= 25, "The play world steps at 60 Hz (${session.scene.world.tick} ticks)")
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
            assertNull(state.play)
            assertEquals(2, state.scene.objects.size, "Play never changes the project")
        }

    @Test
    fun desktopLayoutSelectsByClickAndMovesByDraggingAsOneUndoStep() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            onNodeWithTag("add:Platform").performClick()
            onNodeWithTag("add:Box").performClick()
            waitForIdle()
            val box = checkNotNull(state.selection)
            // Clicking empty space clears the selection; clicking the box (at the view centre) selects it again.
            val viewport = onNodeWithTag("viewport")
            viewport.performTouchInput { click(Offset(40f, 40f)) }
            waitForIdle()
            assertNull(state.selection)
            viewport.performTouchInput { click(center) }
            waitForIdle()
            assertEquals(box, state.selection)
            val before = state.editor.revision
            viewport.performTouchInput { swipe(center, center + Offset(96f, 48f), durationMillis = 300) }
            waitForIdle()
            val transform =
                state.scene
                    .find(box)
                    ?.component("graphics:transform")
                    ?.values
            assertEquals(2.0, transform?.get("x"))
            assertEquals(1.0, transform?.get("y"))
            assertEquals(before + 1, state.editor.revision, "A whole drag is one change")
            assertTrue(state.editor.undoLabel?.startsWith("Move") == true)
            // Two-finger pinch outwards zooms in; the mouse wheel zooms back out.
            viewport.performTouchInput {
                pinch(
                    center - Offset(40f, 0f),
                    center - Offset(160f, 0f),
                    center + Offset(40f, 0f),
                    center + Offset(160f, 0f),
                    durationMillis = 300,
                )
            }
            waitForIdle()
            val pinched = state.camera.zoom
            assertTrue(pinched > 1.5, "Pinch zoomed in (zoom $pinched)")
            assertEquals(box, state.selection, "Pinching never moves or deselects objects")
            viewport.performMouseInput {
                moveTo(center)
                scroll(3f)
            }
            waitForIdle()
            assertTrue(state.camera.zoom < pinched, "Wheel zoomed out (zoom ${state.camera.zoom})")
            onRoot().screenshot("builder-desktop")
        }
}
