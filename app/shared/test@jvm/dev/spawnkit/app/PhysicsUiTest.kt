package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardActions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Builds a small platformer with the generated controls (C14b): a solid platform, a player with a body and a Jump rule,
 * and a camera that follows the player. Then plays it in both layouts: the player lands, jumps and lands again.
 */
@OptIn(ExperimentalTestApi::class)
class PhysicsUiTest {
    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    @Test
    fun aPhoneCreatorCanBuildAndPlayAPlatformer() = checkPlatformer(compact = true)

    @Test
    fun aDesktopCreatorCanBuildAndPlayAPlatformer() = checkPlatformer(compact = false)

    private fun checkPlatformer(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            tap("add:Platform")
            val platform = assertNotNull(state.selection)
            assertNotNull(state.scene.find(platform)?.component("gameplay:solid"), "palette platforms are solid")
            tap("add:Player")
            val player = assertNotNull(state.selection)
            if (compact) tap("tab:Object")
            tap("add-component:gameplay:body")
            tap(if (compact) "tab:Rules" else "panel:Rules")
            tap("add-rule")
            tap("kind:KeyPressed")
            tap("block-done")
            tap("rule:0:add-do")
            tap("kind:Jump")
            tap("block-done")
            assertEquals(
                listOf(RuleItem.Blocks(Rule(Condition.KeyPressed(StandardActions.JUMP), actions = listOf(Action.Jump(15.0))))),
                state.ruleItems(player),
            )
            if (compact) tap("tab:Add")
            tap("add:Camera")
            val camera = assertNotNull(state.selection)
            tap(if (compact) "tab:Object" else "panel:Properties")
            tap("add-component:gameplay:follow")
            assertNotNull(state.scene.find(camera)?.component("gameplay:follow"), "the camera follows the player tag by default")

            val project = state.project
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(1_000)
            val session = assertNotNull(state.play)
            val world = session.scene.world
            val engine = state.engine
            val hero = assertNotNull(session.scene.entity(player))
            fun y() = assertNotNull(world.get(hero, engine.graphics.transform)).y
            val floor = assertNotNull(engine.gameplay.bounds(world, assertNotNull(session.scene.entity(platform))))
            val resting = y()
            assertTrue(abs(floor.top - 0.45 - resting) < 1e-6, "the player stands on the platform")
            assertTrue(assertNotNull(world.get(hero, engine.gameplay.body)).onGround)

            if (compact) {
                val jump = onNodeWithTag("control:${StandardActions.JUMP}")
                jump.performTouchInput { down(center) }
                mainClock.advanceTimeBy(250)
                jump.performTouchInput { up() }
            } else {
                val viewport = onNodeWithTag("viewport")
                viewport.performKeyInput { keyDown(Key.Spacebar) }
                mainClock.advanceTimeBy(250)
                viewport.performKeyInput { keyUp(Key.Spacebar) }
            }
            mainClock.advanceTimeBy(50)
            assertTrue(y() < resting - 1.0, "the player jumped: ${y()} vs $resting")
            val view = assertNotNull(world.get(assertNotNull(session.scene.entity(camera)), engine.graphics.transform))
            assertEquals(y(), view.y, "the camera follows the jump")
            screenshot(if (compact) "physics-play-phone.png" else "physics-play-desktop.png")

            mainClock.advanceTimeBy(2_000)
            assertTrue(abs(resting - y()) < 1e-6, "and lands again")
            assertEquals(null, state.playProblem())
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
            assertEquals(project, state.project, "play never edits the game")
        }

    private fun ComposeUiTest.screenshot(name: String) {
        val file =
            java.io
                .File("build/screenshots")
                .apply { mkdirs() }
                .resolve(name)
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
