package dev.spawnkit.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.spawnkit.editing.OperationResult

/**
 * The game's scenes (C9): tap one to edit it. The first scene is the start scene players see first; "Make start"
 * moves the scene being edited there. Every control is a catalog operation, the same one agents use (ADR-0009).
 */
@Composable
internal fun SceneList(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    val scenes = state.project.scenes
    val index = scenes.indexOfFirst { it.id == state.sceneId }
    val editing = state.play == null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        scenes.forEachIndexed { i, scene ->
            val current = scene.id == state.sceneId
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (current) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .clickable(enabled = editing) { state.selectScene(scene.id) }
                    .padding(horizontal = 12.dp)
                    .testTag("scene:${scene.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(scene.name, Modifier.weight(1f), fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                if (i == 0) Text("Start", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SceneAction("+ Scene", "scene-add", editing) {
                val result = state.applyCatalog("add_scene", mapOf("name" to "Scene ${scenes.size + 1}"))
                (result as? OperationResult.Applied)?.createdIds?.firstOrNull()?.let(state::selectScene)
            }
            SceneAction("Rename", "scene-rename", editing) { renaming = true }
            SceneAction("↑", "scene-up", editing && index > 0) { state.moveScene(index) }
            SceneAction("↓", "scene-down", editing && index < scenes.lastIndex) { state.moveScene(index + 2) }
            SceneAction("Make start", "scene-start", editing && index > 0) { state.moveScene(1) }
            SceneAction("Delete", "scene-delete", editing && scenes.size > 1) {
                state.applyCatalog("remove_scene", mapOf("scene" to state.sceneId))
            }
        }
    }
    if (renaming) RenameScene(state) { renaming = false }
}

@Composable
private fun SceneAction(
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp).testTag(tag), enabled = enabled) { Text(label) }
}

@Composable
private fun RenameScene(
    state: EditorState,
    close: () -> Unit,
) {
    var name by remember { mutableStateOf(state.scene.name) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Name this scene") },
        text = {
            OutlinedTextField(
                name,
                { name = it.take(80) },
                Modifier.testTag("scene-name"),
                singleLine = true,
                label = { Text("Scene name") },
            )
        },
        confirmButton = {
            Button(
                {
                    state.applyCatalog("rename_scene", mapOf("scene" to state.sceneId, "name" to name))
                    close()
                },
                Modifier.testTag("scene-name-done"),
                enabled = name.isNotBlank(),
            ) { Text("Done") }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

/** Moves the scene being edited to position [to] (1 is the start scene). */
private fun EditorState.moveScene(to: Int) {
    applyCatalog("move_scene", mapOf("scene" to sceneId, "to" to to.toLong()))
}
