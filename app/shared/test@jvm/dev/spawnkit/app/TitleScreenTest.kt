package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.DefaultPresets
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.player.playSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Add title screen" builds an ordinary scene that players see first, with a Play button to the game (C9). */
@OptIn(ExperimentalTestApi::class)
class TitleScreenTest {
    private fun EditorState.names() = project.scenes.map { it.name }

    @Test
    fun aTitleScreenIsOneUndoStepAndPlaysToTheLevel() {
        val state = EditorState()
        state.add(DefaultPresets.first { it.name == "Player" })
        val level = state.sceneId

        state.addTitleScreen()

        assertEquals(listOf("Title", "Main scene"), state.names(), "The title screen is the start scene")
        assertEquals("Title", state.scene.name, "The title screen opens for editing")
        assertEquals(listOf("Background", "Title text", "Play button", "Play label"), state.scene.objects.map { it.name })
        val session =
            state.engine.playSession(
                state.project,
                state.project.scenes
                    .first()
                    .id,
            )
        session.pointerDown(1, 0.0, 2.0)
        session.pointerUp(1)
        session.scene.world.submitInput(session.nextInput())
        session.scene.world.step()
        session.afterTick()
        assertEquals(level, session.sceneId, "Tapping Play reaches the level")
        assertNull(session.message)

        state.undo()

        assertEquals(listOf("Main scene"), state.names(), "One undo removes the whole title screen")
        assertEquals(level, state.sceneId)
    }

    @Test
    fun titleScreenIsAddedFromTheSceneListAndPlaysFromIt() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }

            onNodeWithTag("title-add").performClick()
            waitForIdle()
            // The play loop never idles: drive the clock by hand while playing, as the other play tests do.
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(100)

            assertEquals(listOf("Title", "Main scene"), state.names())
            assertEquals(
                state.project.scenes
                    .first()
                    .id,
                state.play?.sceneId,
            )
            onNodeWithTag("stop").performClick()
            mainClock.autoAdvance = true
        }
}
