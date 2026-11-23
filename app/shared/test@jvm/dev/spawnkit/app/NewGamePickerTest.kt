package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.DesktopProjectFile
import dev.spawnkit.app.persistence.ProjectAction
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.presets.GamePreset
import dev.spawnkit.presets.GamePresets
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Project → New game offers a blank game or a preset; a preset is a fresh copy with no file yet (C10). */
@OptIn(ExperimentalTestApi::class)
class NewGamePickerTest {
    private fun preset(id: String) = GamePresets.all.single { it.id == id }

    @Test
    fun aPresetLoadsAsACopyThatSavesToANewFile() {
        val files = TestProjectFiles()
        val session = ProjectSession(files)
        session.save()
        val opened = files.file.read()

        session.chooseNew()
        assertTrue(session.choosing)
        session.startNew(preset("coin-dash"))

        assertFalse(session.choosing)
        assertEquals("Coin Dash", session.state.project.name)
        assertEquals("Title", session.state.scene.name, "the game opens on its title screen")
        assertFalse(session.dirty, "an untouched preset has nothing to save")
        assertNull(session.state.editor.undoLabel, "undo cannot bring back the previous game")
        val copy =
            DesktopProjectFile(Files.createTempDirectory(Files.createDirectories(Path.of("build")), "preset-").resolve("copy.spawnkit"))
        files.choice = copy
        session.save()
        assertEquals(opened, files.file.read(), "the file opened before is untouched")
        assertEquals("Coin Dash", ProjectCodec.decode(copy.read()).name)
    }

    @Test
    fun unsavedChangesAreAskedAboutAndCancelKeepsTheGame() {
        val session = ProjectSession(TestProjectFiles())
        session.state.add(DefaultPresets.first { it.name == "Box" })
        val game = session.state.project
        val undo = session.state.editor.undoLabel

        session.chooseNew()
        session.startNew(preset("tap-targets"))
        assertEquals(ProjectAction.New, session.pending)
        session.cancelNavigation()

        assertEquals(game, session.state.project)
        assertEquals(undo, session.state.editor.undoLabel)
        session.chooseNew()
        session.startNew(preset("tap-targets"))
        session.discardAndContinue()
        assertEquals("Tap Targets", session.state.project.name)
    }

    @Test
    fun anUnreadablePresetKeepsTheGame() {
        val session = ProjectSession(TestProjectFiles())
        val game = session.state.project

        session.chooseNew()
        session.startNew(GamePreset("missing", "Missing", "Not embedded", emptyList()))

        assertEquals(game, session.state.project)
        assertTrue(
            session.state.message
                .orEmpty()
                .contains("could not be opened"),
            session.state.message,
        )
    }

    @Test
    fun blankStartsAnEmptyGame() {
        val session = ProjectSession(TestProjectFiles())
        session.startNew(preset("maze-run"))

        session.chooseNew()
        session.startNew(null)

        assertEquals(Project.blank(), session.state.project)
    }

    @Test fun aPhoneOffersBlankAndEveryPreset() = pick(360, 740, "maze-run", "new-game-picker-phone")

    @Test fun aDesktopOffersBlankAndEveryPreset() = pick(1280, 800, "coin-dash", "new-game-picker-desktop")

    private fun pick(
        width: Int,
        height: Int,
        id: String,
        image: String,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val session = ProjectSession(TestProjectFiles())
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(session.state, projectSession = session) } }

        tap("project-menu")
        tap("project-new")

        onNodeWithTag("new-game-picker").assertIsDisplayed()
        capture(image)
        (listOf("new-blank") + GamePresets.all.map { "new-preset-${it.id}" }).forEach {
            onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        GamePresets.all.forEach { preset ->
            onNodeWithText("Uses: " + preset.teaches.joinToString(" · ")).performScrollTo().assertIsDisplayed()
        }
        // Complete games (C17) are listed first, under their own heading, above the starters. Positions are read without
        // scrolling, so they are all measured in the same scroll state.
        val complete =
            onNodeWithTag("new-group:Complete games")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val starters =
            onNodeWithTag("new-group:Starters")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val tycoon =
            onNodeWithTag("new-preset-toaster-tycoon")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val blank =
            onNodeWithTag("new-blank")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(complete < tycoon && tycoon < blank, "the template sits under Complete games, before the starters")
        assertTrue(starters < blank)
        onNodeWithTag("new-preset-$id").performScrollTo().performClick()
        waitForIdle()
        assertEquals(preset(id).name, session.state.project.name)
        assertEquals("Title", session.state.scene.name)
        assertFalse(session.choosing)
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val destination = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        // Dialogs add a second semantics root to the same offscreen scene; the first root captures everything.
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", destination)
    }
}
