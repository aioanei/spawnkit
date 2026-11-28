package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Direction
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.SpawnPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Creates a template and a moving-copy rule with the generated controls, then plays the game in both layouts. */
@OptIn(ExperimentalTestApi::class)
class SpawningUiTest {
    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    @Test
    fun aPhoneCreatorCanBuildAndPlayASpawner() = checkSpawner(compact = true)

    @Test
    fun aDesktopCreatorCanBuildAndPlayASpawner() = checkSpawner(compact = false)

    private fun checkSpawner(compact: Boolean) =
        runDesktopComposeUiTest(width = if (compact) 412 else 1440, height = if (compact) 915 else 900) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            showPalette()
            tap("add:Score")
            val score = assertNotNull(state.selection)
            if (compact) tap("tab:Object")
            tap("field:$score/gameplay:number/keep")
            assertEquals(
                true,
                state.scene
                    .find(score)
                    ?.component("gameplay:number")
                    ?.values
                    ?.get("keep"),
            )
            if (compact) tap("tab:Add")
            tap("add:Coin")
            val coin = assertNotNull(state.selection)
            if (compact) tap("tab:Object")
            tap("add-component:gameplay:template")
            tap(if (compact) "tab:Rules" else "panel:Rules")
            tap("add-rule")
            tap("kind:LeftArea")
            tap("block-done")
            tap("rule:0:add-do")
            tap("kind:RemoveSelf")
            tap("block-done")
            if (compact) tap("tab:Add")
            tap("add:Player")
            val player = assertNotNull(state.selection)
            tap(if (compact) "tab:Rules" else "panel:Rules")
            tap("add-rule")
            tap("kind:GameStarts")
            tap("block-done")
            tap("rule:0:add-do")
            tap("kind:Spawn")
            tap("tag:coin")
            tap("direction:Down")
            repeat(3) { tap("inc:speed") }
            tap("block-done")
            assertEquals(
                listOf(
                    RuleItem.Blocks(
                        Rule(Condition.GameStarts, actions = listOf(Action.Spawn("coin", SpawnPlace.Self, Direction.Down, 3.0))),
                    ),
                ),
                state.ruleItems(player),
            )
            val project = state.project
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(1_000)
            val session = assertNotNull(state.play)
            assertEquals(null, session.scene.entity(coin), "the template stays out of play")
            val copy =
                state.engine.gameplay
                    .tagged(session.scene.world, "coin")
                    .single()
            assertNotNull(session.scene.world.get(copy, state.engine.programs.behavior), "the copy has its own rules")
            assertTrue(assertNotNull(session.scene.world.get(copy, state.engine.graphics.transform)).y > 0.0)
            assertEquals(null, state.playProblem())
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
            assertEquals(project, state.project, "play never edits the authored template")
        }
}
