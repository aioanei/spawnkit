package dev.spawnkit.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.spawnkit.editing.AdSettings
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneDoc
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Test ads in players (C12a): when they show, what they report, and that interstitials pause the game. */
@OptIn(ExperimentalTestApi::class)
class AdsTest {
    @Test
    fun theBannerShowsOnlyOnTheStartScene() {
        val schedule = AdSchedule(AdSettings(banner = true), "title")

        assertTrue(schedule.bannerOn("title"))
        assertFalse(schedule.bannerOn("level"))
        assertFalse(AdSchedule(AdSettings(), "title").bannerOn("title"), "ads are off by default")
    }

    @Test
    fun interstitialsComeEveryFewChangesButNeverBeforeTheFirstPlay() {
        val every3 = AdSchedule(AdSettings(interstitial = true, every = 3), "title")
        assertEquals(listOf(4, 7, 10), (1..10).filter(every3::interstitialAfter))
        val every1 = AdSchedule(AdSettings(interstitial = true, every = 1), "title")
        assertEquals(listOf(2, 3, 4), (1..4).filter(every1::interstitialAfter), "the change into the first level never shows one")
    }

    /** Two scenes that go to each other on their first moment, so the game changes scene every moment. */
    private fun bouncing(ads: AdSettings): GamePackage {
        fun scene(
            id: String,
            next: String,
        ) = SceneDoc(
            id,
            id,
            listOf(ObjectDoc("o-$id", "Switch", rules = RuleBlocks.compile(listOf(Rule(Condition.GameStarts, actions = listOf(Action.GoToScene(next))))))),
        )
        return GamePackage.of(Project("Ads", listOf(scene("title", "level"), scene("level", "title")), nextId = 10, ads = ads))
    }

    @Test
    fun bannerViewsAndClicksAreReported() =
        runComposeUiTest {
            val events = mutableListOf<AdEvent>()
            // Without a scene change the title stays; GameStarts sends it away, so give it no rules here.
            val still = GamePackage.of(Project("Ads", listOf(SceneDoc("title", "Title")), nextId = 2, ads = AdSettings(banner = true)))
            // The play loop never idles, so drive the clock by hand.
            mainClock.autoAdvance = false
            setContent { GamePlayer(still, touchControls = false, adReporter = { events += it }) }
            mainClock.advanceTimeBy(200)
            onNodeWithTag("ad-banner").assertIsDisplayed()
            onNodeWithTag("ad-banner").performClick()
            mainClock.advanceTimeBy(100)
            mainClock.autoAdvance = true

            assertEquals(listOf(AdEvent(AdPlacement.Banner, AdEventKind.View), AdEvent(AdPlacement.Banner, AdEventKind.Click)), events)
        }

    @Test
    fun anInterstitialPausesTheGameUntilClosed() =
        runComposeUiTest {
            val events = mutableListOf<AdEvent>()
            mainClock.autoAdvance = false
            setContent { GamePlayer(bouncing(AdSettings(interstitial = true, every = 2)), touchControls = false, adReporter = { events += it }) }
            mainClock.advanceTimeBy(500)

            onNodeWithTag("ad-interstitial").assertIsDisplayed()
            assertEquals(listOf(AdEvent(AdPlacement.Interstitial, AdEventKind.View)), events, "shown once, after the third change")
            val before = events.size
            mainClock.advanceTimeBy(500)
            assertEquals(before, events.size, "the game is paused while the ad shows")

            onNodeWithTag("ad-close").performClick()
            mainClock.advanceTimeBy(100)
            assertEquals(2, events.size, "playing on reaches the next interstitial")
            mainClock.autoAdvance = true
        }
}
