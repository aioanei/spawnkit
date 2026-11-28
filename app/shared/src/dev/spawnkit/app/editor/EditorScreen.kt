package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spawnkit.ai.AccountClient
import dev.spawnkit.ai.AccountService
import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.KoogModel
import dev.spawnkit.app.account.AccountScreen
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.account.sharedHttpClient
import dev.spawnkit.app.assistant.AssistantPanel
import dev.spawnkit.app.assistant.AssistantState
import dev.spawnkit.app.assistant.SecretStore
import dev.spawnkit.app.assistant.rememberSecretStore
import dev.spawnkit.app.images.rememberImagePicker
import dev.spawnkit.app.persistence.NewGameAi
import dev.spawnkit.app.persistence.ProjectDialogs
import dev.spawnkit.app.persistence.ProjectMenu
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.app.release.ReleaseManager
import dev.spawnkit.app.release.ReleasePlatform
import dev.spawnkit.app.release.rememberReleasePlatform
import dev.spawnkit.editing.ImageAsset
import dev.spawnkit.editing.ObjectDoc

/** Tabs of the expanded layout's right panel; the assistant comes first (C13b). */
private enum class SidePanel { Assistant, Properties, Rules }

/** Tabs of the compact layout, the assistant first (C13b). */
private val CompactTabs = listOf("AI", "Add", "Scene", "Object", "Rules")

/** Below this width the builder uses the compact (phone) layout. */
private val CompactWidth = 720.dp

/** The SpawnKit builder. One composable, two layouts: compact for phones and expanded for tablets and desktops. */
@Composable
fun EditorScreen(
    state: EditorState = remember { EditorState() },
    modifier: Modifier = Modifier,
    projectSession: ProjectSession? = null,
    releasePlatform: ReleasePlatform = rememberReleasePlatform(),
    secrets: SecretStore = rememberSecretStore(),
    liveModel: (String) -> AgentModel = { KoogModel.anthropic(it) },
    accountService: (String) -> AccountService = { AccountClient(it, sharedHttpClient) },
) {
    var releasing by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accounts = remember(secrets) { AccountState(secrets, scope, accountService) }
    LaunchedEffect(accounts) { accounts.start() }
    val assistant =
        remember(state, secrets) { AssistantState(state, secrets, scope, liveModel, accounts::hostedChoice, accounts::photoForge) }
    val openAccount = { accountOpen = true }
    // Bumped to bring the assistant forward, for example when New game hands it a description (C13b).
    var assistantFocus by remember { mutableIntStateOf(0) }
    val buildRequest = projectSession?.buildRequest
    LaunchedEffect(buildRequest) {
        val request = projectSession?.takeBuildRequest() ?: return@LaunchedEffect
        assistantFocus++
        if (request.replay) {
            assistant.watchReplay()
        } else {
            assistant.prompt = request.prompt
            assistant.ask()
        }
    }
    CompositionLocalProvider(LocalAccounts provides accounts) {
        BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            if (maxWidth < CompactWidth) {
                CompactLayout(state, projectSession, assistant, assistantFocus, openAccount) { releasing = true }
            } else {
                ExpandedLayout(state, projectSession, assistant, assistantFocus, openAccount) { releasing = true }
            }
        }
    }
    if (projectSession != null) ProjectDialogs(projectSession, NewGameAi(ready = !assistant.needsSignIn, signIn = openAccount))
    if (releasing) ReleaseManager(state, releasePlatform, accounts) { releasing = false }
    if (accountOpen) AccountScreen(accounts, secrets.where) { accountOpen = false }
}

@Composable
private fun ExpandedLayout(
    state: EditorState,
    session: ProjectSession?,
    assistant: AssistantState,
    assistantFocus: Int,
    onAccount: () -> Unit,
    onRelease: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Toolbar(state, compact = false, session, onAccount, onRelease)
        Row(Modifier.weight(1f)) {
            Panel(Modifier.width(260.dp).fillMaxHeight()) {
                Column {
                    SectionTitle("Add objects")
                    Palette(state)
                    SectionTitle("Scenes")
                    SceneList(state)
                    SectionTitle("In this scene")
                    Hierarchy(state, Modifier.weight(1f))
                }
            }
            Viewport(state, Modifier.weight(1f).fillMaxHeight())
            Panel(Modifier.width(360.dp).fillMaxHeight()) {
                var panel by remember { mutableStateOf(SidePanel.Assistant) }
                // Selecting an object opens its properties, so manual editing works as before (C13b).
                LaunchedEffect(state.selection) { if (state.selection != null) panel = SidePanel.Properties }
                LaunchedEffect(assistantFocus) { if (assistantFocus > 0) panel = SidePanel.Assistant }
                val askAi = { prompt: String ->
                    assistant.prompt = prompt
                    panel = SidePanel.Assistant
                }
                Column {
                    Row(Modifier.padding(start = 4.dp, top = 4.dp)) {
                        SidePanel.entries.forEach { entry ->
                            TextButton({ panel = entry }, Modifier.testTag("panel:${entry.name}")) {
                                Text(
                                    if (entry == SidePanel.Assistant) "AI" else entry.name,
                                    fontWeight = if (panel == entry) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    when (panel) {
                        SidePanel.Properties -> Inspector(state, Modifier.weight(1f), onAskAi = askAi)
                        SidePanel.Rules -> RulesPanel(state, Modifier.weight(1f), onAskAi = askAi)
                        SidePanel.Assistant -> AssistantPanel(assistant, Modifier.weight(1f))
                    }
                }
            }
        }
        StatusBar(state, session)
    }
}

@Composable
private fun CompactLayout(
    state: EditorState,
    session: ProjectSession?,
    assistant: AssistantState,
    assistantFocus: Int,
    onAccount: () -> Unit,
    onRelease: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(assistantFocus) {
        if (assistantFocus == 0) return@LaunchedEffect
        tab = 0
        expanded = true
    }
    val askAi = { prompt: String ->
        assistant.prompt = prompt
        tab = 0
        expanded = true
    }
    Column(Modifier.fillMaxSize()) {
        Toolbar(state, compact = true, session, onAccount, onRelease)
        Viewport(state, Modifier.weight(1f).fillMaxWidth(), touchControls = true)
        StatusBar(state, session)
        if (state.play == null) {
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TabRow(tab, Modifier.weight(1f)) {
                            CompactTabs.forEachIndexed { index, title ->
                                Tab(
                                    tab == index,
                                    {
                                        tab = index
                                        expanded = true
                                    },
                                    Modifier.testTag("tab:$title"),
                                ) {
                                    Text(title, Modifier.padding(horizontal = 4.dp, vertical = 12.dp), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        TextButton(
                            { expanded = !expanded },
                            Modifier.size(48.dp).testTag("panel-toggle"),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(if (expanded) "▾" else "▴") }
                    }
                    if (expanded) {
                        // The conversation needs more room than the other tabs (C13b).
                        Box(Modifier.height(if (tab == 0) 360.dp else 280.dp).fillMaxWidth()) {
                            when (tab) {
                                0 -> {
                                    AssistantPanel(assistant, Modifier.fillMaxSize())
                                }

                                1 -> {
                                    Palette(state)
                                }

                                2 -> {
                                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                        SceneList(state)
                                        SectionTitle("In this scene")
                                        Hierarchy(state, Modifier.heightIn(max = 400.dp))
                                    }
                                }

                                3 -> {
                                    Inspector(state, Modifier.fillMaxSize(), onAskAi = askAi)
                                }

                                else -> {
                                    RulesPanel(state, Modifier.fillMaxSize(), onAskAi = askAi)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: EditorState,
    compact: Boolean,
    session: ProjectSession?,
    onAccount: () -> Unit,
    onRelease: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (session != null) ProjectMenu(session, compact, onRelease, onAccount)
            Text(
                if (compact && session != null) {
                    ""
                } else if (compact) {
                    "SpawnKit"
                } else {
                    "SpawnKit — ${state.project.name}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val editing = state.play == null
            // Undo/redo availability lives in the editor, so subscribe to every revision explicitly.
            state.revision
            ToolButton("↶", "Undo", enabled = editing && state.editor.undoLabel != null, tag = "undo", compact = compact) { state.undo() }
            ToolButton("↷", "Redo", enabled = editing && state.editor.redoLabel != null, tag = "redo", compact = compact) { state.redo() }
            if (compact && session != null) {
                CompactObjectMenu(state)
            } else {
                ToolButton("⧉", "Duplicate", enabled = editing && state.selection != null, tag = "duplicate", compact = compact) {
                    state.duplicateSelection()
                }
                ToolButton("🗑", "Delete", enabled = editing && state.selection != null, tag = "delete", compact = compact) {
                    state.deleteSelection()
                }
            }
            if (editing) {
                Button({
                    state.startPlay()
                }, Modifier.testTag("play"), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                    Text("▶ Play", maxLines = 1, softWrap = false)
                }
            } else {
                Button({
                    state.stopPlay()
                }, Modifier.testTag("stop"), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("■ Stop", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    tag: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    // Compact symbols on screen; screen readers announce the full description.
    TextButton(
        onClick,
        enabled = enabled,
        modifier =
            Modifier
                .then(
                    if (compact) Modifier.size(48.dp) else Modifier,
                ).testTag(tag)
                .semantics { contentDescription = description },
        contentPadding = if (compact) PaddingValues(0.dp) else ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun CompactObjectMenu(state: EditorState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton("⋯", "Object actions", enabled = state.play == null, tag = "object-menu", compact = true) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Duplicate object") },
                enabled = state.selection != null,
                modifier = Modifier.testTag("duplicate"),
                onClick = {
                    expanded = false
                    state.duplicateSelection()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete object") },
                enabled = state.selection != null,
                modifier = Modifier.testTag("delete"),
                onClick = {
                    expanded = false
                    state.deleteSelection()
                },
            )
        }
    }
}

@Composable
private fun Palette(state: EditorState) {
    val picker = rememberImagePicker()
    val editing = state.play == null
    var renaming by remember { mutableStateOf<ImageAsset?>(null) }
    val accounts = LocalAccounts.current
    var forging by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in DefaultPresets) {
                OutlinedButton({ state.add(preset) }, Modifier.testTag("add:${preset.name}"), enabled = editing) {
                    Text(preset.name)
                }
            }
            if (picker != null) {
                // The Image piece: choose a picture on this device, then it appears in the scene (C15b).
                OutlinedButton(
                    {
                        picker.pick { result ->
                            result
                                .onSuccess { picked -> picked?.let { state.importImage(it.name, it.png, place = true) } }
                                .onFailure { state.message = it.message ?: "This picture could not be imported" }
                        }
                    },
                    Modifier.testTag("add:Image"),
                    enabled = editing,
                ) { Text("Image…") }
                // Character Forge needs a signed-in account on a server that offers it (ADR-0013).
                if (accounts?.signedIn == true && accounts.status?.characterAvailable == true) {
                    OutlinedButton(
                        { forging = true },
                        Modifier.testTag("add:Character"),
                        enabled = editing,
                    ) { Text("Character from photo…") }
                }
            }
        }
        if (picker != null || state.project.images.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Images in this game", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (picker != null) {
                    TextButton(
                        {
                            picker.pick { result ->
                                result
                                    .onSuccess { picked -> picked?.let { state.importImage(it.name, it.png) } }
                                    .onFailure { state.message = it.message ?: "This picture could not be imported" }
                            }
                        },
                        Modifier.testTag("image-import"),
                        enabled = editing,
                    ) { Text("Import…") }
                }
            }
            for (image in state.project.images) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(image.name, Modifier.weight(1f), maxLines = 1)
                    TextButton({ state.addImageObject(image.id) }, Modifier.testTag("image-add:${image.name}"), enabled = editing) {
                        Text("Add")
                    }
                    TextButton({ renaming = image }, Modifier.testTag("image-rename:${image.name}"), enabled = editing) { Text("Rename") }
                    TextButton(
                        { state.applyCatalog("remove_image", mapOf("image" to image.id)) },
                        Modifier.testTag("image-remove:${image.name}"),
                        enabled = editing,
                    ) { Text("Remove") }
                }
            }
        }
    }
    renaming?.let { image -> RenameImage(state, image) { renaming = null } }
    if (forging && accounts != null && picker != null) CharacterForgeDialog(state, accounts, picker) { forging = false }
}

@Composable
private fun RenameImage(
    state: EditorState,
    image: ImageAsset,
    close: () -> Unit,
) {
    var name by remember { mutableStateOf(image.name) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Name this image") },
        text = {
            OutlinedTextField(
                name,
                { name = it.take(80) },
                Modifier.testTag("image-name"),
                singleLine = true,
                label = { Text("Image name") },
            )
        },
        confirmButton = {
            Button(
                {
                    state.applyCatalog("rename_image", mapOf("image" to image.id, "name" to name))
                    close()
                },
                Modifier.testTag("image-name-done"),
            ) { Text("Done") }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

/**
 * The scene's objects as an indented list. Tap a row to select it. Hold a row and drag it onto another to move it
 * into that group, or below the last row to move it back to the top level; the `move_into` operation keeps it where
 * it is on screen (C15).
 */
@Composable
private fun Hierarchy(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    val rows = remember(state.revision, state.sceneId) { flatten(state.scene.objects) }
    if (rows.isEmpty()) {
        Text(
            "Your scene is empty. Add an object to start.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val currentRows by rememberUpdatedState(rows)
    // Each row's top and bottom in root coordinates, so a drag can tell which row it is over.
    val bounds = remember(state.sceneId) { mutableStateMapOf<String, Pair<Float, Float>>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }

    fun over(): ObjectDoc? =
        dragging?.let { moving ->
            currentRows
                .firstOrNull { (obj, _) ->
                    obj.id != moving &&
                        bounds[obj.id]?.let { (top, bottom) -> pointerY in top..bottom } == true
                }?.first
        }

    fun drop() {
        val moving = dragging ?: return
        val target = over()
        dragging = null
        val lowest = currentRows.mapNotNull { bounds[it.first.id]?.second }.maxOrNull() ?: return
        val obj = state.scene.find(moving) ?: return
        when {
            target != null && target.id != obj.parent -> state.moveInto(moving, target.id)
            target == null && pointerY > lowest && obj.parent != null -> state.moveInto(moving, null)
        }
    }
    val target = over()
    LazyColumn(modifier.testTag("hierarchy")) {
        items(rows, key = { it.first.id }) { (obj, depth) ->
            val selected = obj.id == state.selection
            val color =
                when {
                    obj.id == target?.id -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                    obj.id == dragging -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else -> MaterialTheme.colorScheme.surface
                }
            Text(
                obj.name,
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val top = it.positionInRoot().y
                        bounds[obj.id] = top to top + it.size.height
                    }.pointerInput(obj.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at ->
                                dragging = obj.id
                                pointerY = (bounds[obj.id]?.first ?: 0f) + at.y
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                pointerY += amount.y
                            },
                            onDragEnd = { drop() },
                            onDragCancel = { dragging = null },
                        )
                    }.background(color)
                    .clickable { state.selection = obj.id }
                    .padding(start = (12 + depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
                    .testTag("object:${obj.name}"),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        item {
            Text(
                "Hold and drag an object onto another to group them.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Objects in hierarchy order with their depth. */
private fun flatten(objects: List<ObjectDoc>): List<Pair<ObjectDoc, Int>> {
    val result = mutableListOf<Pair<ObjectDoc, Int>>()

    fun visit(
        parent: String?,
        depth: Int,
    ) {
        for (obj in objects.filter { it.parent == parent }) {
            result += obj to depth
            visit(obj.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}

@Composable
private fun StatusBar(
    state: EditorState,
    session: ProjectSession?,
) {
    val text =
        when {
            state.play != null -> playStatus(state)
            state.message != null -> "⚠ ${state.message}"
            state.selection != null -> "Drag to move · pinch or scroll to zoom · drag empty space to pan"
            else -> "Tap an object to select it · ${state.scene.objects.size} objects in ${state.scene.name}"
        }
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
            ).padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("status"),
        style = MaterialTheme.typography.bodySmall,
        color =
            if (state.message != null &&
                state.play == null
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        maxLines = 2,
    )
    if (session != null) {
        Text(
            session.status,
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("project-status"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Panel(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) { content() }
}

/** Status while playing: a rule problem naming its object, or how to stop. Re-read on every presented frame. */
private fun playStatus(state: EditorState): String {
    state.play?.frame
    return state.playProblem()?.let { "⚠ $it" }
        ?: "Playing — press Stop to return to editing. Changes made while playing are not kept."
}
