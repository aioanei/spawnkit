package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spawnkit.app.persistence.ProjectDialogs
import dev.spawnkit.app.persistence.ProjectMenu
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.editing.ObjectDoc

/** Below this width the builder uses the compact (phone) layout. */
private val CompactWidth = 720.dp

/** The SpawnKit builder. One composable, two layouts: compact for phones and expanded for tablets and desktops. */
@Composable
fun EditorScreen(
    state: EditorState = remember { EditorState() },
    modifier: Modifier = Modifier,
    projectSession: ProjectSession? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        if (maxWidth < CompactWidth) CompactLayout(state, projectSession) else ExpandedLayout(state, projectSession)
    }
    if (projectSession != null) ProjectDialogs(projectSession)
}

@Composable
private fun ExpandedLayout(
    state: EditorState,
    session: ProjectSession?,
) {
    Column(Modifier.fillMaxSize()) {
        Toolbar(state, compact = false, session)
        Row(Modifier.weight(1f)) {
            Panel(Modifier.width(260.dp).fillMaxHeight()) {
                Column {
                    SectionTitle("Add objects")
                    Palette(state)
                    SectionTitle("Scene")
                    Hierarchy(state, Modifier.weight(1f))
                }
            }
            Viewport(state, Modifier.weight(1f).fillMaxHeight())
            Panel(Modifier.width(340.dp).fillMaxHeight()) {
                var rules by remember { mutableStateOf(false) }
                Column {
                    Row(Modifier.padding(start = 4.dp, top = 4.dp)) {
                        TextButton({ rules = false }, Modifier.testTag("panel:Properties")) {
                            Text("Properties", fontWeight = if (!rules) FontWeight.Bold else FontWeight.Normal)
                        }
                        TextButton({ rules = true }, Modifier.testTag("panel:Rules")) {
                            Text("Rules", fontWeight = if (rules) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    if (rules) RulesPanel(state, Modifier.weight(1f)) else Inspector(state, Modifier.weight(1f))
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
) {
    var tab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Toolbar(state, compact = true, session)
        Viewport(state, Modifier.weight(1f).fillMaxWidth(), touchControls = true)
        StatusBar(state, session)
        if (state.play == null) {
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TabRow(tab, Modifier.weight(1f)) {
                            listOf("Add", "Scene", "Object", "Rules").forEachIndexed { index, title ->
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
                        Box(Modifier.height(280.dp).fillMaxWidth()) {
                            when (tab) {
                                0 -> Palette(state)
                                1 -> Hierarchy(state, Modifier.fillMaxSize())
                                2 -> Inspector(state, Modifier.fillMaxSize())
                                else -> RulesPanel(state, Modifier.fillMaxSize())
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
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (session != null) ProjectMenu(session, compact)
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
    FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in DefaultPresets) {
            OutlinedButton({ state.add(preset) }, Modifier.testTag("add:${preset.name}"), enabled = state.play == null) {
                Text(preset.name)
            }
        }
    }
}

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
    LazyColumn(modifier.testTag("hierarchy")) {
        items(rows, key = { it.first.id }) { (obj, depth) ->
            val selected = obj.id == state.selection
            Text(
                obj.name,
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface)
                    .clickable { state.selection = obj.id }
                    .padding(start = (12 + depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
                    .testTag("object:${obj.name}"),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
