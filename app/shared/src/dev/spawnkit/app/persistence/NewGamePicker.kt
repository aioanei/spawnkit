package dev.spawnkit.app.persistence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.graphics.CameraView
import dev.spawnkit.graphics.RenderList
import dev.spawnkit.graphics.renderList
import dev.spawnkit.presets.GamePreset
import dev.spawnkit.presets.GamePresets
import dev.spawnkit.render.RuntimeCanvas

/** One choice in the picker: a blank game (`preset == null`) or a preset. */
private class NewGameChoice(
    val preset: GamePreset?,
    val name: String,
    val pitch: String,
    val tag: String,
)

/**
 * Project → New game (C10): a blank game or one of the preset games, each with a picture of its game scene. Phones list
 * the choices in one column; wider screens use two.
 */
@Composable
fun NewGamePicker(
    session: ProjectSession,
    presets: List<GamePreset> = GamePresets.all,
) {
    if (!session.choosing) return
    val complete = presets.filter { it.complete }.map { NewGameChoice(it, it.name, it.pitch, "new-preset-${it.id}") }
    val starters =
        listOf(NewGameChoice(null, "Blank", "An empty scene to build anything.", "new-blank")) +
            presets.filterNot { it.complete }.map { NewGameChoice(it, it.name, it.pitch, "new-preset-${it.id}") }
    // Complete games come first: a home screen, levels, a shop with test purchases, test ads, and endings (C17).
    val groups =
        listOfNotNull(
            complete.takeIf { it.isNotEmpty() }?.let { "Complete games" to it },
            "Starters" to starters,
        )
    val engine = remember { StandardEngine() }
    Dialog(session::closeChooser, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 760.dp).testTag("new-game-picker"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints {
                val columns = if (maxWidth >= 560.dp) 2 else 1
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("New game", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Start from an empty scene, or from a ready-made game you can change in every way.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    for ((heading, choices) in groups) {
                        Text(
                            heading,
                            Modifier.semantics { heading() }.testTag("new-group:$heading"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        choices.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { choice ->
                                    ChoiceCard(choice, engine, wide = columns > 1, Modifier.weight(1f)) { session.startNew(choice.preset) }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    TextButton(session::closeChooser, Modifier.align(Alignment.End).testTag("new-cancel")) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    choice: NewGameChoice,
    engine: StandardEngine,
    wide: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick, modifier.testTag(choice.tag)) {
        val picture = @Composable { pictureModifier: Modifier -> Picture(choice.preset, engine, pictureModifier) }
        if (wide) {
            Column {
                picture(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Labels(choice, Modifier.padding(12.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                picture(Modifier.size(112.dp, 72.dp))
                Labels(choice, Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun Labels(
    choice: NewGameChoice,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(choice.name, style = MaterialTheme.typography.titleMedium)
        Text(choice.pitch, style = MaterialTheme.typography.bodySmall)
        // The blocks the preset shows how to use, named as in the rule editor, so creators know what they will learn.
        choice.preset?.teaches?.takeIf { it.isNotEmpty() }?.let {
            Text(
                "Uses: " + it.joinToString(" · "),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The preset's game scene (its second scene, after the title screen) drawn without running rules; empty for Blank. */
@Composable
private fun Picture(
    preset: GamePreset?,
    engine: StandardEngine,
    modifier: Modifier,
) {
    val frame: RenderList? =
        remember(preset) {
            preset?.let {
                runCatching {
                    val project = it.project()
                    engine.graphics.renderList(buildSceneWorld(engine.runtime, project, project.scenes[1].id).world)
                }.getOrNull()
            }
        }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (frame == null) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = Color(0x99FFFFFF))
        } else {
            // Fit the 9 × 8 unit play area of the presets into the picture.
            val unit = with(LocalDensity.current) { minOf(maxWidth / 9f, maxHeight / 8f).toPx() }
            RuntimeCanvas(frame, Modifier.fillMaxSize(), camera = CameraView(zoom = 1.0), pixelsPerUnit = unit)
        }
    }
}
