package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Direction
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.Target
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Builds games with the visual rule editor, as a creator would, using only taps. */
@OptIn(ExperimentalTestApi::class)
class RuleEditorUiTest {
    private fun ComposeUiTest.tap(tag: String) {
        val node = onNodeWithTag(tag)
        // Nodes in scrolling panels are scrolled into view first; others are tapped directly.
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.tapInDialog(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val file = File("build/screenshots").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    @Test
    fun aScoreAndCollectGameIsBuiltWithTapsOnlyOnAPhoneAndPlays() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            tap("add:Score")
            tap("add:Coin")
            val coin = checkNotNull(state.selection)
            // Move the coin three units right with the inspector's + button.
            tap("tab:Object")
            repeat(6) { tap("inc:$coin/graphics:transform/x") }
            tap("tab:Add")
            tap("add:Player")
            val player = checkNotNull(state.selection)

            // Player: When holding Right, move right.
            tap("tab:Rules")
            tap("add-rule")
            tapInDialog("kind:KeyHeld")
            tapInDialog("action:${StandardActions.RIGHT}")
            tapInDialog("block-done")
            tap("rule:0:add-do")
            tapInDialog("kind:Move")
            tapInDialog("direction:Right")
            tapInDialog("block-done")
            assertEquals(
                listOf(
                    RuleItem.Blocks(Rule(Condition.KeyHeld(StandardActions.RIGHT), actions = listOf(Action.Move(Direction.Right, 3.0)))),
                ),
                state.ruleItems(player),
            )

            // Coin: When touching player, add 1 to score, show it, and disappear.
            tap("tab:Scene")
            tap("object:Coin")
            tap("tab:Rules")
            tap("add-rule")
            tapInDialog("kind:Touching")
            tapInDialog("tag:player")
            tapInDialog("block-done")
            tap("rule:0:add-do")
            tapInDialog("kind:ChangeNumber")
            tapInDialog("target:score")
            tapInDialog("block-done")
            tap("rule:0:add-do")
            tapInDialog("kind:ShowNumber")
            tapInDialog("target:score")
            tapInDialog("block-done")
            tap("rule:0:add-do")
            tapInDialog("kind:RemoveSelf")
            tapInDialog("block-done")
            assertEquals(
                listOf(
                    RuleItem.Blocks(
                        Rule(
                            Condition.Touching("player"),
                            actions =
                                listOf(
                                    Action.ChangeNumber(Target.Tagged("score"), 1),
                                    Action.ShowNumber(Target.Tagged("score"), "Score: "),
                                    Action.RemoveSelf,
                                ),
                        ),
                    ),
                ),
                state.ruleItems(coin),
            )
            onNodeWithTag("rule:0:do:1").assertTextContains("Show score", substring = true)
            screenshot("builder-rules-phone")

            // Undo removes the last block; redo brings it back.
            tap("undo")
            assertEquals(2, (state.ruleItems(coin).single() as RuleItem.Blocks).rule.actions.size)
            tap("redo")

            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(100)
            val right = onNodeWithTag("control:${StandardActions.RIGHT}")
            right.performTouchInput { down(center) }
            mainClock.advanceTimeBy(2_000)
            right.performTouchInput { up() }
            mainClock.advanceTimeBy(100)
            val session = assertNotNull(state.play)
            val score = checkNotNull(state.scene.objects.firstOrNull { it.name == "Score" }).id
            val entity = checkNotNull(session.scene.entity(score))
            assertEquals(
                1L,
                session.scene.world
                    .get(entity, state.engine.gameplay.number)
                    ?.value,
            )
            assertEquals(
                "Score: 1",
                session.scene.world
                    .get(entity, state.engine.graphics.text)
                    ?.text,
            )
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
        }

    @Test
    fun blocksCanBeChangedAndRemovedAndAdvancedRulesAreKept() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val state = EditorState()
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            tap("add:Player")
            val player = checkNotNull(state.selection)
            val advanced =
                dev.spawnkit.program.Handler(
                    dev.spawnkit.program.Trigger.EveryTick,
                    emptyList(),
                    dev.spawnkit.program.SourceRef("custom", "Made by an agent"),
                )
            state.setRuleItems(
                player,
                listOf(RuleItem.Advanced(advanced), RuleItem.Blocks(Rule(Condition.Tapped, actions = listOf(Action.RemoveSelf)))),
            )
            waitForIdle()
            tap("panel:Rules")
            onNodeWithTag("rule:0:advanced").assertTextContains("Made by an agent", substring = true)
            // Change the When block of the block rule from Tapped to Every 2 seconds.
            tap("rule:1:if:0")
            tapInDialog("kind:EverySeconds")
            tapInDialog("inc:seconds")
            tapInDialog("block-done")
            // Add an "And if", then remove the action.
            tap("rule:1:add-if")
            tapInDialog("kind:GameStarts")
            tapInDialog("block-done")
            tap("rule:1:do:0")
            tapInDialog("block-remove")
            assertEquals(
                listOf(RuleItem.Advanced(advanced), RuleItem.Blocks(Rule(Condition.EverySeconds(2), listOf(Condition.GameStarts)))),
                state.ruleItems(player),
            )
            tap("rule:1:delete")
            assertEquals(
                listOf<RuleItem>(RuleItem.Advanced(advanced)),
                state.ruleItems(player),
                "Deleting a block rule keeps advanced rules",
            )
        }
}
