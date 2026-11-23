package dev.spawnkit.app.release

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spawnkit.ai.AccountException
import dev.spawnkit.ai.AdStatsReport
import dev.spawnkit.ai.ArcadeGame
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.AdSettings
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.releaseProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination { Web, Arcade }

private data class ReleaseResult(
    val message: String,
    val failed: Boolean = false,
    val playUrl: String? = null,
)

private class ReleaseSession(
    val state: EditorState,
    val platform: ReleasePlatform,
    private val scope: CoroutineScope,
    val accounts: AccountState?,
) {
    /** Signed in: the Arcade is the account's server, and games are published as its own (C11b). */
    val signedIn: Boolean get() = accounts?.signedIn == true

    var title by mutableStateOf(state.project.name)

    // Released games start where players should start: the first scene, for example a title screen.
    var sceneId by mutableStateOf(
        state.project.scenes
            .first()
            .id,
    )
    var address by mutableStateOf("")
    var active by mutableStateOf<Destination?>(null)
    private var job: Job? = null
    var progress by mutableStateOf("")
    var webResult by mutableStateOf<ReleaseResult?>(null)
    var arcadeResult by mutableStateOf<ReleaseResult?>(null)

    fun cancel() {
        job?.cancel()
    }

    /** Removes [game] from the Arcade; its play link stops working. */
    fun unpublish(game: ArcadeGame) {
        val owner = accounts ?: return
        if (active != null) return
        active = Destination.Arcade
        progress = "Unpublishing \"${game.title}\"…"
        arcadeResult = null
        job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    owner.unpublish(game.slug)
                    arcadeResult = ReleaseResult("Unpublished \"${game.title}\". Its play link no longer works.")
                } catch (cancelled: CancellationException) {
                    arcadeResult = ReleaseResult("Cancelled. Check My games before trying again.")
                    throw cancelled
                } catch (problem: AccountException) {
                    arcadeResult = ReleaseResult(problem.message ?: "The game could not be unpublished.", failed = true)
                } finally {
                    active = null
                    job = null
                }
            }
    }

    /** Releases the game; with [update], it replaces that owned Arcade game and keeps its play link. */
    fun release(
        destination: Destination,
        update: ArcadeGame? = null,
    ) {
        if (active != null) return
        val owner = accounts?.takeIf { it.signedIn }
        val snapshot = state.editor.saveSnapshot()
        val releaseTitle = title
        val start = sceneId
        val arcade = address.trim()
        active = destination
        progress = "Checking your game…"
        if (destination == Destination.Web) webResult = null else arcadeResult = null
        job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val encoded =
                        withContext(Dispatchers.Default) {
                            val pkg = GamePackage.of(snapshot, releaseTitle, start)
                            pkg.releaseProblem(state.engine.runtime, state.engine.host)?.let { throw IllegalArgumentException(it) }
                            GamePackageCodec.encode(pkg)
                        }
                    when (destination) {
                        Destination.Web -> {
                            progress = "Saving your web game…"
                            webResult =
                                if (platform.exportWeb("${releaseTitle.trim()}-web.zip", encoded)) {
                                    ReleaseResult("Web game saved. Unzip the files and upload them together to a web host.")
                                } else {
                                    ReleaseResult("Export cancelled.")
                                }
                        }

                        Destination.Arcade -> {
                            progress = "Uploading your game…"
                            arcadeResult =
                                when {
                                    owner != null && update != null -> {
                                        val game = owner.republish(update.slug, encoded)
                                        ReleaseResult(
                                            "Updated \"${update.title}\" on the Arcade. Its play link is the same.",
                                            playUrl = game.playUrl,
                                        )
                                    }

                                    owner != null -> {
                                        ReleaseResult("Published to the Arcade as your game.", playUrl = owner.publish(encoded).playUrl)
                                    }

                                    else -> {
                                        ReleaseResult("Published to the Arcade.", playUrl = platform.publish(arcade, encoded))
                                    }
                                }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (destination == Destination.Web) {
                        webResult = ReleaseResult("Export cancelled.")
                    } else {
                        arcadeResult = ReleaseResult("Publishing cancelled. Check the Arcade before publishing again.")
                    }
                    throw cancelled
                } catch (problem: Exception) {
                    val detail =
                        when (problem) {
                            is IllegalArgumentException, is IllegalStateException, is AccountException -> problem.message
                            else -> null
                        }
                    val result = ReleaseResult(detail ?: "The release could not finish. Try again.", failed = true)
                    if (destination == Destination.Web) webResult = result else arcadeResult = result
                } finally {
                    active = null
                    job = null
                }
            }
    }
}

/** Releases the current workspace snapshot without changing the project or its undo history. */
@Composable
internal fun ReleaseManager(
    state: EditorState,
    platform: ReleasePlatform,
    accounts: AccountState? = null,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(state, platform, scope, accounts) { ReleaseSession(state, platform, scope, accounts) }
    val browser = LocalUriHandler.current
    Dialog(
        onDismissRequest = { if (session.active == null) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("release-manager"), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                BoxWithConstraints(Modifier.widthIn(max = 1000.dp).fillMaxWidth()) {
                    val expanded = maxWidth >= 720.dp
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = if (expanded) 32.dp else 16.dp, vertical = 8.dp),
                    ) {
                        TextButton(close, Modifier.testTag("release-back"), enabled = session.active == null) { Text("Back to editor") }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Release Manager", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                            GameSettings(session)
                            TestAdsCard(session.state, enabled = session.active == null)
                            if (!platform.available) Text("Open this project in the Android or desktop builder to export or publish.")
                            if (expanded) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    WebCard(session, Modifier.weight(1f))
                                    ArcadeCard(session, Modifier.weight(1f), browser::openUri)
                                }
                            } else {
                                WebCard(session, Modifier.fillMaxWidth())
                                ArcadeCard(session, Modifier.fillMaxWidth(), browser::openUri)
                            }
                            DestinationCard("Store builds", Modifier.fillMaxWidth()) {
                                Text("These destinations require the planned Kotlin Toolchain build service.")
                                Text("Android · App ID, icon and signing key for an APK or Play Store bundle.")
                                Text("iOS · Bundle ID, signing and an Apple Developer account for TestFlight or the App Store.")
                                Text("Desktop · Target operating system, app icon and signing for an installer.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameSettings(session: ReleaseSession) {
    OutlinedTextField(
        session.title,
        { session.title = it },
        Modifier.fillMaxWidth().testTag("release-title"),
        enabled = session.active == null,
        label = { Text("Game title") },
        singleLine = true,
    )
    val project = session.state.project
    if (project.scenes.size > 1) {
        var choosing by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ choosing = true }, enabled = session.active == null) {
                Text("Start scene · ${project.scene(session.sceneId)?.name}")
            }
            DropdownMenu(choosing, { choosing = false }) {
                project.scenes.forEach { scene ->
                    DropdownMenuItem(text = { Text(scene.name) }, onClick = {
                        session.sceneId = scene.id
                        choosing = false
                    })
                }
            }
        }
    }
}

@Composable
private fun WebCard(
    session: ReleaseSession,
    modifier: Modifier,
) {
    DestinationCard("Web export", modifier) {
        Text("Save a ZIP with your game and its browser player. You can export offline.")
        Button(
            { session.release(Destination.Web) },
            Modifier.testTag("release-export"),
            enabled = session.platform.available && session.active == null,
        ) { Text("Save web ZIP") }
        if (session.active == Destination.Web) ReleaseProgress(session.progress, session::cancel)
        session.webResult?.let { ResultText(it) }
    }
}

@Composable
private fun ArcadeCard(
    session: ReleaseSession,
    modifier: Modifier,
    openLink: (String) -> Unit,
) {
    val owner = session.accounts?.takeIf { session.signedIn }
    // Signed in, publishing goes through the account's client, which works on every platform.
    val enabled = (session.platform.available || owner != null) && session.active == null
    val destination = owner?.server ?: session.address.trim()
    DestinationCard("SpawnKit Arcade", modifier) {
        if (owner != null) {
            Text("Publishing as ${owner.account?.displayName} to ${owner.server}. Anyone with the play link can open it.")
        } else {
            Text("Publish your game to this server. Anyone with the play link can open it. Sign in with Project → Account to own it.")
            OutlinedTextField(
                session.address,
                { session.address = it },
                Modifier.fillMaxWidth().testTag("release-address"),
                enabled = enabled,
                singleLine = true,
                label = { Text("Arcade server address") },
                placeholder = { Text("https://arcade.example.org") },
            )
        }
        var confirming by remember { mutableStateOf(false) }
        Button(
            { confirming = true },
            Modifier.testTag("release-publish"),
            enabled = enabled && destination.isNotBlank(),
        ) { Text(if (owner != null) "Publish as a new game" else "Publish game") }
        // Publishing is outward-facing, so it always needs an explicit confirmation (ADR-0009).
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                modifier = Modifier.testTag("publish-confirm"),
                title = { Text("Publish to the Arcade?") },
                text = { Text("Publish \"${session.title.trim()}\" to $destination? Anyone with the play link can play it.") },
                confirmButton = {
                    Button(
                        {
                            confirming = false
                            session.release(Destination.Arcade)
                        },
                        Modifier.testTag("publish-confirm-yes"),
                    ) { Text("Publish") }
                },
                dismissButton = { TextButton({ confirming = false }, Modifier.testTag("publish-confirm-no")) { Text("Cancel") } },
            )
        }
        if (session.active == Destination.Arcade) ReleaseProgress(session.progress, session::cancel)
        session.arcadeResult?.let { result ->
            ResultText(result)
            result.playUrl?.let { PlayLink(it, openLink) }
        }
        if (owner != null) MyGames(session, owner, enabled, openLink)
    }
}

/** The account's Arcade games: open, update with this project, or unpublish; updates and removals are confirmed. */
@Composable
private fun MyGames(
    session: ReleaseSession,
    owner: AccountState,
    enabled: Boolean,
    openLink: (String) -> Unit,
) {
    LaunchedEffect(owner) { owner.refreshGames() }
    var updating by remember { mutableStateOf<ArcadeGame?>(null) }
    var statistics by remember { mutableStateOf<ArcadeGame?>(null) }
    var removing by remember { mutableStateOf<ArcadeGame?>(null) }
    Text("My games", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    if (owner.games.isEmpty()) Text("Games you publish while signed in appear here.", style = MaterialTheme.typography.bodySmall)
    owner.games.forEach { game ->
        Column(Modifier.fillMaxWidth().testTag("my-game"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(game.title, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ runCatching { openLink(game.playUrl) } }, Modifier.heightIn(min = 48.dp)) { Text("Open") }
                OutlinedButton(
                    { updating = game },
                    Modifier.heightIn(min = 48.dp).testTag("my-game-update-${game.slug}"),
                    enabled = enabled,
                ) {
                    Text("Update with this game")
                }
                TextButton({ statistics = game }, Modifier.heightIn(min = 48.dp).testTag("my-game-stats-${game.slug}")) {
                    Text("Ad statistics")
                }
                TextButton(
                    { removing = game },
                    Modifier.heightIn(min = 48.dp).testTag("my-game-unpublish-${game.slug}"),
                    enabled = enabled,
                ) {
                    Text("Unpublish")
                }
            }
        }
    }
    updating?.let { game ->
        AlertDialog(
            onDismissRequest = { updating = null },
            title = { Text("Update \"${game.title}\"?") },
            text = {
                Text(
                    "Replace \"${game.title}\" on the Arcade with \"${session.title.trim()}\" as it is now? Its play link stays the same.",
                )
            },
            confirmButton = {
                Button(
                    {
                        updating = null
                        session.release(Destination.Arcade, update = game)
                    },
                    Modifier.testTag("update-confirm-yes"),
                ) { Text("Update") }
            },
            dismissButton = { TextButton({ updating = null }, Modifier.testTag("update-confirm-no")) { Text("Cancel") } },
        )
    }
    statistics?.let { game -> AdStatistics(owner, game) { statistics = null } }
    removing?.let { game ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unpublish \"${game.title}\"?") },
            text = { Text("Players can no longer open it, and its play link stops working.") },
            confirmButton = {
                Button(
                    {
                        removing = null
                        session.unpublish(game)
                    },
                    Modifier.testTag("unpublish-confirm-yes"),
                ) { Text("Unpublish") }
            },
            dismissButton = { TextButton({ removing = null }, Modifier.testTag("unpublish-confirm-no")) { Text("Cancel") } },
        )
    }
}

/**
 * Where the game shows test ads (C12): the `set_ads` operation, the same one agents use. Test ads are labelled panels
 * that earn nothing; only plays on the Arcade are counted.
 */
@Composable
private fun TestAdsCard(
    state: EditorState,
    enabled: Boolean,
) {
    val ads = state.project.ads

    fun set(
        banner: Boolean = ads.banner,
        interstitial: Boolean = ads.interstitial,
        every: Int = ads.every,
    ) {
        state.applyCatalog("set_ads", mapOf("banner" to banner, "interstitial" to interstitial, "every" to every.toLong()))
    }
    DestinationCard("Test ads", Modifier.fillMaxWidth()) {
        Text(
            "Labelled test ads, off until you turn them on. They earn nothing; plays on the Arcade are counted so you can " +
                "see views and clicks, and your own previews never are.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Banner on the title screen", Modifier.weight(1f))
            Switch(ads.banner, { set(banner = it) }, Modifier.testTag("ads-banner"), enabled = enabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Full-screen ad between scenes", Modifier.weight(1f))
            Switch(ads.interstitial, { set(interstitial = it) }, Modifier.testTag("ads-interstitial"), enabled = enabled)
        }
        if (ads.interstitial) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Every ${ads.every} scene changes, never before the first play", Modifier.weight(1f))
                OutlinedButton(
                    { set(every = ads.every - 1) },
                    Modifier.testTag("ads-every-dec"),
                    enabled = enabled && ads.every > 1,
                ) { Text("−") }
                OutlinedButton(
                    { set(every = ads.every + 1) },
                    Modifier.testTag("ads-every-inc"),
                    enabled = enabled && ads.every < AdSettings.MAX_EVERY,
                ) { Text("+") }
            }
        }
    }
}

/**
 * Test ad statistics of [game] from Arcade plays (C12): views, clicks and click-through rate per day, revenue that is
 * always $0.00, and an optional What if line that is clearly an estimate.
 */
@Composable
private fun AdStatistics(
    owner: AccountState,
    game: ArcadeGame,
    close: () -> Unit,
) {
    var report by remember(game) { mutableStateOf<AdStatsReport?>(null) }
    var problem by remember(game) { mutableStateOf<String?>(null) }
    var rate by remember(game) { mutableStateOf("") }
    LaunchedEffect(game) {
        try {
            report = owner.adStats(game.slug)
        } catch (failure: AccountException) {
            problem = failure.message
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Ad statistics · ${game.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("ad-stats"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val current = report
                if (current == null && problem == null) Text("Loading…")
                if (current != null) {
                    Text(
                        "Views ${current.views} · Clicks ${current.clicks} · Click-through rate ${clickRate(
                            current.views,
                            current.clicks,
                        )}",
                    )
                    Text("Revenue: $0.00 — test ads earn nothing", Modifier.testTag("ad-revenue"), fontWeight = FontWeight.SemiBold)
                    Text(
                        "Test purchases ${current.purchases} — no money is charged",
                        Modifier.testTag("purchase-total"),
                    )
                    for (item in current.purchaseDays) Text("${item.day}: ${item.count} × ${item.product}")
                    if (current.days.isEmpty()) Text("No ad views yet. Plays on the Arcade are counted; previews are not.")
                    for (day in current.days) {
                        Text("${day.day}: ${day.views} views, ${day.clicks} clicks, ${clickRate(day.views, day.clicks)}")
                    }
                    OutlinedTextField(
                        rate,
                        { rate = it.take(8) },
                        Modifier.fillMaxWidth().testTag("what-if-rate"),
                        label = { Text("What if: dollars per 1,000 views") },
                        singleLine = true,
                    )
                    rate.toDoubleOrNull()?.takeIf { it >= 0 && it < 1000 }?.let { perThousand ->
                        val cents = (current.views * perThousand / 10).toLong()
                        Text(
                            "About $${cents / 100}.${(cents % 100).toString().padStart(2, '0')} — an estimate, not earnings",
                            Modifier.testTag("what-if-estimate"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(close, Modifier.testTag("ad-stats-close")) { Text("Close") } },
    )
}

private fun clickRate(
    views: Long,
    clicks: Long,
): String = if (views == 0L) "—" else "${(clicks * 1000 / views) / 10}.${(clicks * 1000 / views) % 10}%"

@Composable
private fun DestinationCard(
    title: String,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun ReleaseProgress(
    message: String,
    cancel: () -> Unit,
) {
    LinearProgressIndicator(Modifier.fillMaxWidth())
    ResultText(ReleaseResult(message))
    OutlinedButton(cancel, Modifier.testTag("release-cancel")) { Text("Cancel") }
}

@Composable
private fun ResultText(result: ReleaseResult) {
    Text(
        result.message,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (result.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PlayLink(
    url: String,
    openLink: (String) -> Unit,
) {
    // ClipEntry has no common text constructor across the supported Compose targets.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var feedback by remember(url) { mutableStateOf<ReleaseResult?>(null) }
    SelectionContainer { Text(url, Modifier.testTag("release-link")) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            feedback =
                try {
                    openLink(url)
                    null
                } catch (_: Exception) {
                    ReleaseResult("Could not open a browser. Copy the play link to open it yourself.", failed = true)
                }
        }) { Text("Open game") }
        TextButton({
            feedback =
                try {
                    clipboard.setText(AnnotatedString(url))
                    ReleaseResult("Play link copied.")
                } catch (_: Exception) {
                    ReleaseResult("Select the play link above and copy it.", failed = true)
                }
        }) { Text("Copy link") }
    }
    feedback?.let { ResultText(it) }
}
