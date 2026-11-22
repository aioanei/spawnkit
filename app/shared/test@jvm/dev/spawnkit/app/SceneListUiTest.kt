package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.BlockLocation
import dev.spawnkit.gameplay.BlockPart
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleOperations
import kotlin.test.Test
import kotlin.test.assertEquals

/** The scene list at phone and desktop sizes (C9). */
@OptIn(ExperimentalTestApi::class)
class SceneListUiTest {
    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        // The phone panel scrolls; the desktop scene list is always visible.
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private fun EditorState.names() = project.scenes.map { it.name }

    @Test
    fun phoneAddsRenamesAndSwitchesScenes() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            onNodeWithTag("tab:Scene").performClick()
            onNodeWithTag("scene-delete").assertIsNotEnabled()

            tap("scene-add")
            assertEquals(listOf("Main scene", "Scene 2"), state.names())
            assertEquals("Scene 2", state.scene.name, "A new scene opens for editing")
            tap("scene-rename")
            onNodeWithTag("scene-name").performTextReplacement("Level 2")
            tap("scene-name-done")
            tap("scene:Main scene")

            assertEquals(listOf("Main scene", "Level 2"), state.names())
            assertEquals("Main scene", state.scene.name)
        }

    @Test
    fun aGoToSceneWhoseSceneWasRemovedIsARuleProblem() {
        val state = EditorState()
        state.add(DefaultPresets.first { it.name == "Box" })
        val box = checkNotNull(state.selection)
        val level = (state.apply(EditOperation.AddScene("Level")) as OperationResult.Applied).createdIds.single()
        state.apply(RuleOperations.addRule(state.project, state.sceneId, box, Rule(Condition.Tapped, actions = listOf(Action.GoToScene(level)))))
        assertEquals(emptyList(), state.ruleProblems(box))

        state.apply(EditOperation.RemoveScene(level))

        assertEquals(
            listOf(BlockLocation(0, BlockPart.Action, 0) to "Go to scene: that scene was removed. Choose another scene."),
            state.ruleProblems(box),
        )
    }

    @Test
    fun desktopMakesAStartSceneAndDeletesWithUndo() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }

            tap("scene-add")
            tap("scene-start")
            assertEquals(listOf("Scene 2", "Main scene"), state.names(), "The first scene is the start scene")
            tap("scene-delete")
            assertEquals(listOf("Main scene"), state.names())
            assertEquals("Main scene", state.scene.name, "Editing falls back to the remaining scene")
            tap("undo")

            assertEquals(listOf("Scene 2", "Main scene"), state.names())
        }
}
