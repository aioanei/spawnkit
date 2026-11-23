package dev.spawnkit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.spawnkit.editing.AdSettings
import dev.spawnkit.gameplay.RuleBlocks

/** Where an ad appears (C12). */
enum class AdPlacement(
    val id: String,
) {
    /** A banner while the start scene (the title screen) plays. */
    Banner("banner"),

    /** A full-screen ad between scenes. */
    Interstitial("interstitial"),
}

/** What happened to an ad: it was seen, or tapped. */
enum class AdEventKind(
    val id: String,
) {
    View("view"),
    Click("click"),
}

/** One ad view or click, with no personal data. */
data class AdEvent(
    val placement: AdPlacement,
    val kind: AdEventKind,
)

/** Receives ad views and clicks. The Arcade's web player reports them; builder previews and offline games pass none. */
fun interface AdReporter {
    fun report(event: AdEvent)
}

/** The explicit outcome of asking an [AdHost] for an ad. */
sealed interface AdResult {
    /** An ad is shown, with the [label] drawn on it. */
    data class Shown(
        val label: String,
    ) : AdResult

    /** No ad is shown, and why. */
    data class NotShown(
        val reason: String,
    ) : AdResult
}

/** Where ads come from. Today only [TestAdHost]; a real network (AdMob, K6) plugs in behind the same interface. */
fun interface AdHost {
    fun request(placement: AdPlacement): AdResult
}

/** The label on every test ad. */
const val TEST_AD_LABEL = "TEST AD — SpawnKit"

/** Test ads: a labelled panel, with no ad SDK and no third-party calls. They earn nothing. */
val TestAdHost = AdHost { AdResult.Shown(TEST_AD_LABEL) }

/**
 * When a game shows ads, from its [settings]: the banner only on [startScene], and an interstitial after every
 * `every` scene changes, never on the first change, so never before the first play.
 */
class AdSchedule(
    private val settings: AdSettings,
    private val startScene: String?,
) {
    /** Whether the banner shows while scene [sceneId] plays. */
    fun bannerOn(sceneId: String?): Boolean = settings.banner && sceneId != null && sceneId == startScene

    /** Whether an interstitial is due after scene change number [change] (the first change is 1). */
    fun interstitialAfter(change: Int): Boolean = settings.interstitial && change >= 2 && (change - 1) % settings.every == 0
}

/**
 * Draws the game's ads over [session]: the banner along the top of the start scene, and full-screen interstitials
 * between scenes, which pause the game until closed. Views and clicks go to [reporter] when there is one.
 */
@Composable
fun AdLayer(
    session: PlaySession,
    settings: AdSettings,
    startScene: String?,
    reporter: AdReporter?,
    host: AdHost = TestAdHost,
    modifier: Modifier = Modifier,
) {
    if (!settings.banner && !settings.interstitial) return
    val schedule = remember(settings, startScene) { AdSchedule(settings, startScene) }
    var interstitial by remember(session) { mutableStateOf<AdResult.Shown?>(null) }
    LaunchedEffect(session, session.sceneChanges) {
        if (!schedule.interstitialAfter(session.sceneChanges)) return@LaunchedEffect
        val result = host.request(AdPlacement.Interstitial) as? AdResult.Shown ?: return@LaunchedEffect
        interstitial = result
        session.paused = true
        reporter?.report(AdEvent(AdPlacement.Interstitial, AdEventKind.View))
    }
    Box(modifier.fillMaxSize()) {
        if (schedule.bannerOn(session.sceneId) && interstitial == null) {
            val banner = remember(session.sceneChanges) { host.request(AdPlacement.Banner) as? AdResult.Shown }
            if (banner != null) {
                LaunchedEffect(session.sceneChanges) { reporter?.report(AdEvent(AdPlacement.Banner, AdEventKind.View)) }
                AdPanel(
                    banner.label,
                    "A test ad. It earns nothing.",
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .heightIn(min = 56.dp)
                        .testTag("ad-banner")
                        .clickable { reporter?.report(AdEvent(AdPlacement.Banner, AdEventKind.Click)) },
                )
            }
        }
        interstitial?.let { shown ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xE6101418))
                    .padding(24.dp)
                    .testTag("ad-interstitial"),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AdPanel(
                    shown.label,
                    "A full-screen test ad between scenes. It earns nothing.",
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .clickable { reporter?.report(AdEvent(AdPlacement.Interstitial, AdEventKind.Click)) },
                )
                Button(
                    {
                        interstitial = null
                        session.paused = false
                    },
                    Modifier.heightIn(min = 48.dp).testTag("ad-close"),
                ) { Text("Close ad") }
            }
        }
    }
}

/** Receives completed test purchases (C12). The Arcade's web player reports them; previews and offline games pass none. */
fun interface PurchaseReporter {
    fun purchased(
        product: String,
        priceCents: Long,
    )
}

/** The label on every test purchase. */
const val TEST_PURCHASE_LABEL = "TEST PURCHASE: no money is charged"

/**
 * Asks the player to confirm a test purchase a rule offered (C12b): "Buy ⟨product⟩ for ⟨price⟩?", labelled
 * [TEST_PURCHASE_LABEL]. Buying grants the item through its number; cancelling grants nothing. Play is paused meanwhile.
 */
@Composable
fun PurchaseLayer(
    session: PlaySession,
    reporter: PurchaseReporter?,
    modifier: Modifier = Modifier,
) {
    val request = session.pendingPurchase ?: return
    val action = request.action
    Column(
        modifier.fillMaxSize().background(Color(0xE6101418)).padding(24.dp).testTag("purchase"),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(TEST_PURCHASE_LABEL, color = Color(0xFFFFF59D), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "Buy ${action.product} for ${RuleBlocks.price(action.priceCents)}?",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("This is a test: nothing is paid, and the item is still granted.", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Button(
            { session.completePurchase(buy = true)?.let { reporter?.purchased(it.action.product, it.action.priceCents) } },
            Modifier.heightIn(min = 48.dp).testTag("purchase-buy"),
        ) { Text("Buy (test)") }
        Button({ session.completePurchase(buy = false) }, Modifier.heightIn(min = 48.dp).testTag("purchase-cancel")) { Text("Cancel") }
    }
}

@Composable
private fun AdPanel(
    label: String,
    note: String,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(Color(0xFFFFF59D))
            .border(2.dp, Color(0xFF5D4037))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0xFF3E2723), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(note, color = Color(0xFF4E342E), style = MaterialTheme.typography.bodySmall)
    }
}
