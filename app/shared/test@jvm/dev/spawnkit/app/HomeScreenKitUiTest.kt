package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The home screen kit by hand (C15), in both layouts: Button and Text pieces, "Move into…", dragging in the scene
 * list, and the full title screen layout.
 */
@OptIn(ExperimentalTestApi::class)
class HomeScreenKitUiTest {
    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    /** Holds the row of [name] and drags it by [dy] pixels, as a finger would. */
    private fun ComposeUiTest.drag(
        name: String,
        dy: Float,
    ) {
        val row = onNodeWithTag("object:$name")
        runCatching { row.performScrollTo() }
        row.performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(0f, dy / 2))
            moveBy(Offset(0f, dy / 2))
            up()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.centerY(name: String): Float =
        onNodeWithTag("object:$name")
            .fetchSemanticsNode()
            .boundsInRoot.center.y

    @Test
    fun aPhoneCreatorBuildsAHomeScreen() = checkKit(compact = true)

    @Test
    fun aDesktopCreatorBuildsAHomeScreen() = checkKit(compact = false)

    private fun checkKit(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }

            fun named(name: String) = assertNotNull(state.scene.objects.firstOrNull { it.name == name }, name)

            showPalette()
            tap("add:Button")
            tap("add:Text")
            assertEquals(listOf("Button", "Button label", "Text"), state.scene.objects.map { it.name })
            assertEquals(named("Button").id, named("Button label").parent, "a button comes with its label inside")

            tap(if (compact) "tab:Object" else "panel:Properties")
            tap("move-into")
            tap("move-into:Button")
            assertEquals(named("Button").id, named("Text").parent, "Move into… groups the text with the button")

            if (compact) tap("tab:Scene")
            drag("Text", 600f)
            assertNull(named("Text").parent, "dragging below the list moves it back to the top level")
            drag("Text", centerY("Button") - centerY("Text"))
            assertEquals(named("Button").id, named("Text").parent, "dragging onto the button groups them again")
            state.undo()
            assertNull(named("Text").parent, "each move is one undo step")

            if (compact) tap("tab:Scene")
            tap("title-add")
            tap("title-layout:Full")
            assertEquals(listOf("Title", "Main scene", "Levels", "Shop", "How it works"), state.project.scenes.map { it.name })
            assertEquals("Title", state.scene.name)
            val file =
                java.io
                    .File("build/screenshots")
                    .apply { mkdirs() }
                    .resolve(if (compact) "home-screen-phone.png" else "home-screen-desktop.png")
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        }
}
