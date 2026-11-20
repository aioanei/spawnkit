package dev.spawnkit.app.persistence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.RecoveryResult
import kotlinx.coroutines.delay

/** Project actions fit the existing toolbar on phones and desktops, with full text labels in the menu. */
@Composable
fun ProjectMenu(
    session: ProjectSession,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    TextButton(
        { expanded = true },
        Modifier
            .then(if (compact) Modifier.size(48.dp) else Modifier)
            .testTag("project-menu")
            .semantics { contentDescription = "Project" },
        enabled = !session.busy && session.state.play == null && session.recovery == null,
        contentPadding = if (compact) PaddingValues(0.dp) else ButtonDefaults.TextButtonContentPadding,
    ) { Text(if (compact) "☰" else "Project ▾", maxLines = 1, softWrap = false) }
    DropdownMenu(expanded, { expanded = false }) {
        Text(session.state.project.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        MenuAction("New game", "project-new") {
            expanded = false
            session.request(ProjectAction.New)
        }
        MenuAction("Open project file…", "project-open", session.files.picker != null) {
            expanded = false
            session.request(ProjectAction.Open)
        }
        MenuAction("Save", "project-save", session.files.picker != null) {
            expanded = false
            session.save()
        }
        MenuAction("Save a copy…", "project-copy", session.files.picker != null) {
            expanded = false
            session.save(copy = true)
        }
        MenuAction("Rename game…", "project-rename") {
            expanded = false
            renaming = true
        }
        Text(session.status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (session.files.picker == null) {
            Text(
                "File import/export is available on Android and desktop.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (renaming) RenameGame(session) { renaming = false }
}

@Composable
private fun MenuAction(
    label: String,
    tag: String,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    DropdownMenuItem(text = { Text(label) }, onClick = action, modifier = Modifier.testTag(tag), enabled = enabled)
}

@Composable
private fun RenameGame(
    session: ProjectSession,
    close: () -> Unit,
) {
    var name by remember { mutableStateOf(session.state.project.name) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Name your game") },
        text = {
            OutlinedTextField(
                name,
                {
                    name = it
                    problem = null
                },
                Modifier.fillMaxWidth().testTag("project-name"),
                label = { Text("Game name") },
                singleLine = true,
                isError = problem != null,
                supportingText = { problem?.let { Text(it) } },
            )
        },
        confirmButton = {
            Button({
                when (val result = session.state.apply(EditOperation.RenameProject(name))) {
                    is OperationResult.Applied -> close()
                    is OperationResult.Rejected -> problem = result.message
                }
            }, Modifier.testTag("project-name-done")) { Text("Done") }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

/** Debounced autosave and explicit recovery/navigation decisions. No dialog silently discards an existing draft. */
@Composable
fun ProjectDialogs(session: ProjectSession) {
    val state = session.state
    LaunchedEffect(state.revision, session.recovery) {
        delay(500)
        session.autosave()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.autosave() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.autosave()
        }
    }
    when (val recovery = session.recovery) {
        is RecoveryResult.Found -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Continue your game?") },
                text = {
                    Text(
                        if (recovery.fromPrevious) {
                            "The newest draft couldn't be opened. A previous valid draft of ${recovery.project.name} is available. " +
                                "The damaged data will be kept."
                        } else {
                            "A draft of ${recovery.project.name} was saved on this device. Restore it to continue where you left off."
                        },
                    )
                },
                confirmButton = { Button(session::restoreRecovery, Modifier.testTag("recovery-restore")) { Text("Restore draft") } },
                dismissButton = { TextButton(session::startWithoutRecovery, Modifier.testTag("recovery-new")) { Text("Start new") } },
            )
        }

        is RecoveryResult.Unreadable -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Recovery needs attention") },
                text = { Text(recovery.message) },
                confirmButton = {
                    Button(
                        session::startWithoutRecovery,
                        Modifier.testTag("recovery-new"),
                    ) { Text("Start new; keep recovery data") }
                },
            )
        }

        else -> {}
    }
    val action = session.pending ?: return
    AlertDialog(
        onDismissRequest = session::cancelNavigation,
        title = { Text("Save your changes?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save a project file before ${if (action == ProjectAction.New) "starting a new game" else "opening another game"}.")
                Text(
                    "Cancel keeps your current game open. Discard continues without saving these changes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            if (session.files.picker !=
                null
            ) {
                Button(
                    session::saveAndContinue,
                    Modifier.testTag("changes-save"),
                    enabled = !session.busy,
                ) { Text("Save and continue") }
            }
        },
        dismissButton = {
            Column {
                TextButton(session::cancelNavigation, Modifier.testTag("changes-cancel"), enabled = !session.busy) { Text("Cancel") }
                TextButton(
                    session::discardAndContinue,
                    Modifier.testTag("changes-discard"),
                    enabled = !session.busy,
                ) { Text("Discard changes") }
            }
        },
    )
}
