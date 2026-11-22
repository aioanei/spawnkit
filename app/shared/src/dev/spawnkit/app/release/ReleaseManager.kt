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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spawnkit.app.editor.EditorState
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
) {
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

    fun release(destination: Destination) {
        if (active != null) return
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
                            arcadeResult = ReleaseResult("Published to the Arcade.", playUrl = platform.publish(arcade, encoded))
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
                            is IllegalArgumentException, is IllegalStateException -> problem.message
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
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(state, platform, scope) { ReleaseSession(state, platform, scope) }
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
    val enabled = session.platform.available && session.active == null
    DestinationCard("SpawnKit Arcade", modifier) {
        Text("Publish your game to this server. Anyone with the play link can open it.")
        OutlinedTextField(
            session.address,
            { session.address = it },
            Modifier.fillMaxWidth().testTag("release-address"),
            enabled = enabled,
            singleLine = true,
            label = { Text("Arcade server address") },
            placeholder = { Text("https://arcade.example.org") },
        )
        var confirming by remember { mutableStateOf(false) }
        Button(
            { confirming = true },
            Modifier.testTag("release-publish"),
            enabled = enabled && session.address.isNotBlank(),
        ) { Text("Publish game") }
        // Publishing is outward-facing, so it always needs an explicit confirmation (ADR-0009).
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                modifier = Modifier.testTag("publish-confirm"),
                title = { Text("Publish to the Arcade?") },
                text = { Text("Publish \"${session.title.trim()}\" to ${session.address.trim()}? Anyone with the play link can play it.") },
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
    }
}

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
