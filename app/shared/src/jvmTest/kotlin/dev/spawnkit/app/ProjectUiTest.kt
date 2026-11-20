package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.DesktopDraftStore
import dev.spawnkit.app.persistence.DesktopProjectFile
import dev.spawnkit.app.persistence.ProjectAction
import dev.spawnkit.app.persistence.ProjectFile
import dev.spawnkit.app.persistence.ProjectFilePicker
import dev.spawnkit.app.persistence.ProjectFiles
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.editing.ProjectJournal
import dev.spawnkit.editing.RecoveryResult
import dev.spawnkit.editing.SceneDoc
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.Target
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real desktop files and storage; test pickers select them deterministically instead of showing an OS dialog. */
private class TestProjectFiles : ProjectFiles {
    private val directory = Files.createTempDirectory(Path.of("build"), "project-ui-")
    override val drafts = DesktopDraftStore(directory.resolve("recovery"))
    val file = DesktopProjectFile(directory.resolve("test.spawnkit"))
    var choice: ProjectFile? = file
    override val picker =
        object : ProjectFilePicker {
            override fun open(result: (Result<ProjectFile?>) -> Unit) {
                result(Result.success(choice))
            }

            override fun create(
                suggestedName: String,
                result: (Result<ProjectFile?>) -> Unit,
            ) {
                result(Result.success(choice))
            }
        }
}

@OptIn(ExperimentalTestApi::class)
class ProjectUiTest {
    @Test
    fun aNarrowPhoneKeepsPlayReadableWithScaledTextAndObjectActionsAvailable() =
        runDesktopComposeUiTest(width = 360, height = 915) {
            val session = ProjectSession(TestProjectFiles())
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(session.state, projectSession = session) }
                }
            }
            tap("add:Player")
            val bounds = onNodeWithTag("play").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height <= 60f, "Play must stay on one line")
            assertTrue(bounds.right <= 360f, "Play must fit the phone")
            assertTrue(onNodeWithTag("tab:Rules").fetchSemanticsNode().boundsInRoot.height <= 60f, "Tabs must not split words")
            capture("project-toolbar-compact")
            tap("object-menu")
            tap("duplicate")
            assertEquals(2, session.state.scene.objects.size)
            tap("object-menu")
            tap("delete")
            assertEquals(1, session.state.scene.objects.size)
            tap("undo")
            assertEquals(2, session.state.scene.objects.size)
        }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.menu(tag: String) {
        tap("project-menu")
        tap(tag)
    }

    private fun ComposeUiTest.capture(name: String) {
        val destination = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        // Menus/dialogs add a second semantics root to the same offscreen scene.
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", destination)
    }

    private fun fileRoundTrip(
        width: Int,
        height: Int,
        image: String,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val files = TestProjectFiles()
        val session = ProjectSession(files)
        val state = session.state
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state, projectSession = session) } }
        tap("add:Score")
        val score = checkNotNull(state.selection)
        state.setRuleItems(
            score,
            listOf(
                RuleItem.Blocks(
                    Rule(
                        Condition.GameStarts,
                        actions = listOf(Action.SetNumber(Target.Self, 9), Action.ShowNumber(Target.Self, "Score: ")),
                    ),
                ),
            ),
        )
        waitForIdle()
        menu("project-rename")
        onNodeWithTag("project-name").performTextReplacement("Nine stars")
        tap("project-name-done")
        val expected = state.editor.saveSnapshot()
        tap("project-menu")
        capture(image)
        tap("project-save")
        assertEquals(expected, ProjectCodec.decode(files.file.read()))
        assertFalse(session.dirty)
        menu("project-new")
        assertTrue(state.scene.objects.isEmpty())
        menu("project-open")
        assertEquals(expected, state.project)
        assertNull(state.editor.undoLabel)
        mainClock.autoAdvance = false
        onNodeWithTag("play").performClick()
        mainClock.advanceTimeBy(150)
        val world = assertNotNull(state.play).scene
        val entity = assertNotNull(world.entity(score))
        assertEquals("Score: 9", world.world.get(entity, state.engine.graphics.text)?.text)
        onNodeWithTag("stop").performClick()
        mainClock.advanceTimeBy(100)
        mainClock.autoAdvance = true
        assertEquals(expected, state.project)
    }

    @Test fun aPhoneSavesOpensAndPlaysItsRules() = fileRoundTrip(412, 915, "project-menu-phone")

    @Test fun aDesktopSavesOpensAndPlaysItsRules() = fileRoundTrip(1440, 900, "project-menu-desktop")

    @Test
    fun cancellationBadFilesAndWriteFailuresKeepTheGameAndUndoHistory() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val files = TestProjectFiles()
            val session = ProjectSession(files)
            val state = session.state
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state, projectSession = session) } }
            tap("add:Player")
            val expected = state.project
            val undo = state.editor.undoLabel
            menu("project-new")
            tap("changes-cancel")
            assertEquals(expected, state.project)
            files.choice = null
            menu("project-save")
            assertTrue(session.dirty)
            assertEquals(expected, state.project)
            menu("project-open")
            tap("changes-discard")
            assertEquals(expected, state.project)
            assertEquals(undo, state.editor.undoLabel)
            files.file.write("{bad data")
            files.choice = files.file
            menu("project-open")
            tap("changes-discard")
            assertEquals(expected, state.project)
            assertEquals(undo, state.editor.undoLabel)
            onNodeWithTag("status").assertTextContains("could not be opened", substring = true)
            files.choice =
                object : ProjectFile {
                    override val name = "Read only"

                    override fun read(): String = files.file.read()

                    override fun write(text: String): Unit = error("Read only")
                }
            menu("project-save")
            assertEquals(expected, state.project)
            assertTrue(session.dirty)
            onNodeWithTag("status").assertTextContains("could not be saved", substring = true)
            assertEquals(expected, assertIs<RecoveryResult.Found>(ProjectJournal(files.drafts).load()).project)
        }

    @Test
    fun restartRecoveryAndUndoRedoAutosavesKeepTheWorkspace() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val files = TestProjectFiles()
            val beforeRestart = ProjectSession(files)
            beforeRestart.state.add(DefaultPresets.first { it.name == "Player" })
            beforeRestart.autosave()
            val expected = beforeRestart.state.project
            val session = ProjectSession(files)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(session.state, projectSession = session) } }
            capture("project-recovery-phone")
            tap("recovery-restore")
            assertEquals(expected, session.state.project)
            assertTrue(session.dirty)
            tap("add:Coin")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            val edited = session.state.project
            tap("undo")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertEquals(
                1,
                assertIs<RecoveryResult.Found>(ProjectJournal(files.drafts).load())
                    .project.scenes[0]
                    .objects.size,
            )
            tap("redo")
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertEquals(edited, assertIs<RecoveryResult.Found>(ProjectJournal(files.drafts).load()).project)
        }

    @Test
    fun unavailableComponentsArePreservedAndBlockPlayWithAnExplanation() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val files = TestProjectFiles()
            val project =
                Project(
                    "Extension game",
                    listOf(
                        SceneDoc(
                            "scene",
                            "Main",
                            listOf(
                                ObjectDoc(
                                    "obj",
                                    "Magic",
                                    components =
                                        listOf(
                                            ComponentDoc(
                                                "extension:magic",
                                                mapOf("power" to 7L),
                                            ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
            files.file.write(ProjectCodec.encode(project))
            val session = ProjectSession(files)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(session.state, projectSession = session) } }
            menu("project-open")
            tap("play")
            assertNull(session.state.play)
            onNodeWithTag("status").assertTextContains("extension:magic", substring = true)
            assertEquals(project, session.state.project)
            menu("project-save")
            assertEquals(project, ProjectCodec.decode(files.file.read()))
        }
}
