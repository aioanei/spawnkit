package dev.spawnkit.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.spawnkit.ai.AccountException
import dev.spawnkit.ai.CharacterResult
import dev.spawnkit.app.account.AccountState
import dev.spawnkit.app.images.ImagePicker
import dev.spawnkit.app.images.PickedImage
import kotlinx.coroutines.launch

/** The signed-in account, for builder tools that use the server, such as Character Forge (ADR-0013). */
internal val LocalAccounts = compositionLocalOf<AccountState?> { null }

/** Character Forge styles, in the order they are offered. */
internal val ForgeStyles = listOf("pixel" to "Pixel art", "cartoon" to "Cartoon", "chibi" to "Chibi")

/**
 * "Character from photo" (C16a, ADR-0013): choose a photo, confirm consent, choose styles and make the characters
 * through the server. The images join the game and the chosen one becomes a Character object in one undo step.
 */
@Composable
internal fun CharacterForgeDialog(
    state: EditorState,
    accounts: AccountState,
    picker: ImagePicker,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<PickedImage?>(null) }
    var consent by remember { mutableStateOf(false) }
    var styles by remember { mutableStateOf(setOf("pixel")) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CharacterResult?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!working) close() },
        title = { Text("Character from photo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("forge"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val made = result
                if (made == null) {
                    Text(
                        "Your photo is sent only to the image model to draw these characters, and is not saved. " +
                            "Each style uses part of the shared SpawnKit AI allowance.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        {
                            picker.pick { picked ->
                                picked
                                    .onSuccess { photo = it ?: photo }
                                    .onFailure { problem = it.message ?: "This photo could not be read" }
                            }
                        },
                        Modifier.heightIn(min = 48.dp).testTag("forge-photo"),
                        enabled = !working,
                    ) { Text(photo?.let { "Photo: ${it.name}" } ?: "Choose a photo") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, { consent = it }, Modifier.testTag("forge-consent"), enabled = !working)
                        Text("This is me, or this person agreed")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in ForgeStyles) {
                            FilterChip(
                                id in styles,
                                { styles = if (id in styles) styles - id else styles + id },
                                { Text(label) },
                                Modifier.testTag("forge-style:$id"),
                                enabled = !working,
                            )
                        }
                    }
                } else {
                    if (made.images.isEmpty()) Text("No character could be made.")
                    for (image in made.images) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${image.label} character", Modifier.weight(1f))
                            Button(
                                {
                                    state.addCharacterImages(made.images, image.style)
                                    close()
                                },
                                Modifier.heightIn(min = 48.dp).testTag("forge-use:${image.style}"),
                            ) { Text("Use") }
                        }
                    }
                    for ((style, reason) in made.refused) {
                        Text(
                            "${ForgeStyles.firstOrNull { it.first == style }?.second ?: style}: $reason",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (working) Text("Drawing your characters… this can take a minute.")
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (result == null) {
                Button(
                    {
                        val picked = photo ?: return@Button
                        working = true
                        problem = null
                        scope.launch {
                            try {
                                result = accounts.forge(picked.png, ForgeStyles.map { it.first }.filter { it in styles }, consent)
                            } catch (failure: AccountException) {
                                problem = failure.message
                            } catch (failure: IllegalArgumentException) {
                                problem = failure.message
                            } finally {
                                working = false
                            }
                        }
                    },
                    Modifier.testTag("forge-make"),
                    enabled = photo != null && consent && styles.isNotEmpty() && !working,
                ) { Text("Make characters") }
            }
        },
        dismissButton = {
            TextButton(close, Modifier.testTag("forge-close"), enabled = !working) {
                Text(
                    if (result ==
                        null
                    ) {
                        "Cancel"
                    } else {
                        "Close"
                    },
                )
            }
        },
    )
}
