package dev.spawnkit.app.persistence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.spawnkit.ai.WholeGameReplay
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.graphics.CameraView
import dev.spawnkit.graphics.RenderList
import dev.spawnkit.graphics.renderList
import dev.spawnkit.presets.GamePreset
import dev.spawnkit.presets.GamePresets
import dev.spawnkit.render.RuntimeCanvas

/** What New game offers above the presets (C13b): building from a description, or how to get there. */
class NewGameAi(
    /** Whether a model can build from a description now: SpawnKit AI for the account, or a developer key. */
    val ready: Boolean,
    /** Opens Project → Account. */
    val signIn: () -> Unit,
)

/** One choice in the picker: a blank game (`preset == null`) or a preset. */
private class NewGameChoice(
    val preset: GamePreset?,
    val name: String,
    val pitch: String,
    val tag: String,
)

/**
 * Project → New game (C10, C13b). With [ai], it starts with "Describe your game…": the assistant builds the game on a new
 * blank one. Below are a blank game and the preset games, each with a picture of its game scene. Phones list the choices
 * in one column; wider screens use two.
 */
@Composable
fun NewGamePicker(
    session: ProjectSession,
    presets: List<GamePreset> = GamePresets.all,
    ai: NewGameAi? = null,
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
                    if (ai != null) Describe(session, ai)
                    Text(
                        if (ai != null) {
                            "Or start from an empty scene, or from a ready-made game you can change in every way."
                        } else {
                            "Start from an empty scene, or from a ready-made game you can change in every way."
                        },
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

/** "Describe your game…": the assistant builds it on a new blank game; signed out, how to get SpawnKit AI (C13b). */
@Composable
private fun Describe(
    session: ProjectSession,
    ai: NewGameAi,
) {
    var text by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Describe your game…", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "The assistant plans it, then builds every scene with the builder's own tools: a title screen, levels, a shop " +
                    "and endings. You can change everything by hand afterwards.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                text,
                { text = it.take(MAX_DESCRIPTION) },
                Modifier.fillMaxWidth().testTag("new-describe"),
                placeholder = { Text("For example: a jungle platformer where you collect bananas and dodge snakes") },
                minLines = 2,
                maxLines = 5,
            )
            if (ai.ready) {
                Button(
                    { session.describe(BuildRequest(text.trim())) },
                    Modifier.heightIn(min = 48.dp).testTag("new-build"),
                    enabled = text.isNotBlank(),
                ) { Text("✨ Build it") }
            } else {
                Text(
                    "Building with AI needs a free SpawnKit account, so everyone's limits work.",
                    Modifier.testTag("new-sign-in-hint"),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        {
                            session.closeChooser()
                            ai.signIn()
                        },
                        Modifier.heightIn(min = 48.dp).testTag("new-sign-in"),
                    ) { Text("Sign in") }
                    OutlinedButton(
                        { session.describe(BuildRequest(WholeGameReplay.prompt, replay = true)) },
                        Modifier.heightIn(min = 48.dp).testTag("new-watch-replay"),
                    ) { Text("Watch the AI build a game") }
                }
                Text(
                    "The replay shows a recorded session: no model is called.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Longest description accepted, in characters. */
private const val MAX_DESCRIPTION = 2000
