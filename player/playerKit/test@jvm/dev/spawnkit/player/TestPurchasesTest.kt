package dev.spawnkit.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneDoc
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.gameplay.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test purchases in players (C12b): asked, labelled, granted only when bought, and play pauses meanwhile. */
@OptIn(ExperimentalTestApi::class)
class TestPurchasesTest {
    private val shop =
        Project(
            "Shop",
            listOf(
                SceneDoc(
                    "shop",
                    "Shop",
                    listOf(
                        ObjectDoc(
                            "coins",
                            "Coins",
                            components = listOf(ComponentDoc("gameplay:tag", mapOf("tag" to "coins")), ComponentDoc("gameplay:number")),
                        ),
                        ObjectDoc(
                            "pack",
                            "Coin pack",
                            rules =
                                RuleBlocks.compile(
                                    listOf(
                                        Rule(
                                            Condition.GameStarts,
                                            actions = listOf(Action.TestPurchase("100 coins", 99, Target.Tagged("coins"), 100)),
                                        ),
                                    ),
                                ),
                        ),
                    ),
                ),
            ),
            nextId = 10,
        )

    private fun PlaySession.tick() {
        scene.world.submitInput(nextInput())
        scene.world.step()
        afterTick()
    }

    private fun PlaySession.coins(engine: StandardEngine): Long =
        scene.world.get(engine.gameplay.tagged(scene.world, "coins").single(), engine.gameplay.number)!!.value

    @Test
    fun buyingGrantsTheItemAndCancellingDoesNot() {
        val engine = StandardEngine()
        val bought = engine.playSession(shop, "shop")
        bought.tick()
        assertNotNull(bought.pendingPurchase)
        assertTrue(bought.paused, "play waits for the answer")
        assertEquals("100 coins", bought.completePurchase(buy = true)?.action?.product)
        assertEquals(100L, bought.coins(engine))
        assertTrue(!bought.paused)

        val cancelled = engine.playSession(shop, "shop")
        cancelled.tick()
        assertNull(cancelled.completePurchase(buy = false))
        assertEquals(0L, cancelled.coins(engine))
    }

    @Test
    fun anAdAndAPurchaseEachKeepPlayPausedUntilBothAreDone() {
        val session = StandardEngine().playSession(shop, "shop")
        session.tick()
        session.adShowing = true
        assertTrue(session.paused)
        session.adShowing = false
        assertTrue(session.paused, "the purchase question still holds the game")
        session.adShowing = true
        session.completePurchase(buy = false)
        assertTrue(session.paused, "the ad still holds the game")
        session.adShowing = false
        assertTrue(!session.paused)
    }

    @Test
    fun theDialogIsLabelledAndReportsOnlyPurchases() =
        runComposeUiTest {
            val reported = mutableListOf<Pair<String, Long>>()
            mainClock.autoAdvance = false
            setContent {
                GamePlayer(GamePackage.of(shop), touchControls = false, purchaseReporter = { product, cents ->
                    reported +=
                        product to cents
                })
            }
            mainClock.advanceTimeBy(300)
            onNodeWithText(TEST_PURCHASE_LABEL).assertIsDisplayed()
            onNodeWithText("Buy 100 coins for $0.99?").assertIsDisplayed()
            onNodeWithTag("purchase-buy").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true

            assertEquals(listOf("100 coins" to 99L), reported)
        }
}
