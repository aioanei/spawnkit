package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.program.BinaryOp
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.TextValue
import dev.spawnkit.program.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plays projects whose rules were set through editor operations, with real keyboard, pointer and touch-control input. */
@OptIn(ExperimentalTestApi::class)
class PlayRulesUiTest {
    private fun s(id: String) = SourceRef(id, id)

    private fun self() = Expr.Self(s("self"))

    private fun call(
        name: String,
        vararg args: Expr,
    ) = Expr.CallHost(name, args.toList(), s("call:$name"))

    private fun text(v: String) = Expr.Literal(TextValue(v), s("text"))

    private fun everyTickIf(
        condition: Expr,
        vararg then: Stmt,
    ) = Handler(Trigger.EveryTick, listOf(Stmt.If(condition, then.toList(), emptyList(), s("if"))), s("rule"))

    private fun addTo(
        target: Expr,
        property: String,
        amount: Expr,
    ) = Stmt.SetProperty(
        target,
        property,
        Expr.Binary(BinaryOp.Add, Expr.GetProperty(target, property, s("get")), amount, s("add")),
        s("set:$property"),
    )

    private fun EditorState.object_(
        name: String,
        x: Double,
        tag: String,
        vararg extra: ComponentDoc,
    ): String {
        val components =
            listOf(
                ComponentDoc("graphics:transform", mapOf("x" to x, "y" to 0.0)),
                ComponentDoc("graphics:shape"),
                ComponentDoc("gameplay:tag", mapOf("tag" to tag)),
            ) + extra
        return assertIs<OperationResult.Applied>(apply(EditOperation.AddObject(sceneId, name, components = components))).createdIds.single()
    }

    private fun EditorState.rules(
        objectId: String,
        vararg rules: Handler,
    ) = assertIs<OperationResult.Applied>(apply(EditOperation.SetRules(sceneId, objectId, rules.toList())))

    /** Player moves right while "right" is held; the coin adds a point to the score label's number and disappears. */
    private fun coinGame(state: EditorState): String {
        val player = state.object_("Player", -3.0, "player")
        state.rules(
            player,
            everyTickIf(call("isDown", text(StandardActions.RIGHT)), addTo(self(), "x", Expr.Literal(DecimalValue(0.25), s("speed")))),
        )
        val score =
            state.object_(
                "Score",
                0.0,
                "score",
                ComponentDoc("gameplay:number"),
                ComponentDoc("graphics:text", mapOf("text" to "Score: 0")),
            )
        // The score label sits above the play area so the player never touches it.
        state.apply(EditOperation.SetProperty(state.sceneId, score, "graphics:transform", "y", -4.0))
        val coin = state.object_("Coin", 0.0, "coin")
        val scoreObject = call("firstTagged", text("score"))
        state.rules(
            coin,
            everyTickIf(
                call("touching", text("player")),
                addTo(scoreObject, "number", Expr.Literal(IntegerValue(1), s("one"))),
                Stmt.SetProperty(
                    scoreObject,
                    "text_text",
                    Expr.Binary(BinaryOp.Add, text("Score: "), call("textOf", Expr.GetProperty(scoreObject, "number", s("n"))), s("label")),
                    s("set:label"),
                ),
                Stmt.Destroy(self(), s("remove")),
            ),
        )
        return score
    }

    private fun EditorState.playText(objectId: String): String? {
        val session = checkNotNull(play)
        return session.scene.world
            .get(checkNotNull(session.scene.entity(objectId)), engine.graphics.text)
            ?.text
    }

    private fun EditorState.playNumber(objectId: String): Long? {
        val session = checkNotNull(play)
        return session.scene.world
            .get(checkNotNull(session.scene.entity(objectId)), engine.gameplay.number)
            ?.value
    }

    @Test
    fun holdingRightWithTheKeyboardCollectsTheCoinAndUpdatesTheScore() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val state = EditorState()
            val score = coinGame(state)
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(100)
            val viewport = onNodeWithTag("viewport")
            viewport.performKeyInput { keyDown(Key.DirectionRight) }
            mainClock.advanceTimeBy(1_000)
            viewport.performKeyInput { keyUp(Key.DirectionRight) }
            mainClock.advanceTimeBy(100)
            assertEquals(1L, state.playNumber(score))
            assertEquals("Score: 1", state.playText(score))
            assertEquals(0, state.engine.gameplay.countTagged(checkNotNull(state.play).scene.world, "coin"))
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
            assertEquals(3, state.scene.objects.size, "Playing never changes the project")
        }

    @Test
    fun aQuickTapOnAnObjectIsSeenByItsRules() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val state = EditorState()
            val button = state.object_("Button", 0.0, "button", ComponentDoc("gameplay:number"))
            state.rules(button, everyTickIf(call("tapped"), addTo(self(), "number", Expr.Literal(IntegerValue(1), s("one")))))
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(100)
            // Down and up arrive between two ticks; the press must still reach the rules exactly once.
            onNodeWithTag("viewport").performTouchInput { click(center) }
            mainClock.advanceTimeBy(200)
            assertEquals(1L, state.playNumber(button))
            onNodeWithTag("viewport").performTouchInput { click(centerLeft) }
            mainClock.advanceTimeBy(200)
            assertEquals(1L, state.playNumber(button), "Taps elsewhere do not count")
            mainClock.autoAdvance = true
        }

    @Test
    fun phoneTouchControlsDriveThePlayerAndRuleFaultsAreExplained() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val state = EditorState()
            val score = coinGame(state)
            val broken = state.object_("Broken", 6.0, "broken")
            state.rules(
                broken,
                everyTickIf(
                    Expr.Literal(dev.spawnkit.program.BoolValue(true), s("always")),
                    Stmt.SetProperty(call("firstTagged", text("missing")), "x", Expr.Literal(DecimalValue(1.0), s("one")), s("block:bad")),
                ),
            )
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(state) } }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithTag("play").performClick()
            mainClock.advanceTimeBy(100)
            val right = onNodeWithTag("control:${StandardActions.RIGHT}")
            right.performTouchInput { down(center) }
            mainClock.advanceTimeBy(1_000)
            right.performTouchInput { up() }
            mainClock.advanceTimeBy(100)
            assertEquals("Score: 1", state.playText(score))
            assertNotNull(state.play)
            onNodeWithTag("status").assertTextContains("Broken", substring = true)
            onNodeWithTag("status").assertTextContains("missing", substring = true)
            val file =
                java.io
                    .File("build/screenshots")
                    .apply { mkdirs() }
                    .resolve("builder-play-phone.png")
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            onNodeWithTag("stop").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true
            assertTrue(state.play == null)
        }
}
