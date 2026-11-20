package dev.spawnkit.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.spawnkit.app.editor.EditorScreen
import dev.spawnkit.app.persistence.ProjectSession
import dev.spawnkit.app.persistence.rememberProjectFiles

/** SpawnKit's creator palette: a calm dark workspace with bright accents that keep the game content in focus. */
private val SpawnKitColors =
    darkColorScheme(
        primary = Color(0xFF4FC3F7),
        secondary = Color(0xFFB39DDB),
        tertiary = Color(0xFF66BB6A),
        background = Color(0xFF121318),
        surface = Color(0xFF1A1C22),
        surfaceVariant = Color(0xFF2A2D36),
        error = Color(0xFFEF5350),
    )

/**
 * Root composable of the SpawnKit creator application. The same builder runs on Android, iOS, desktop and web;
 * layouts adapt to the window size rather than per platform.
 */
@Composable
fun App() {
    val files = rememberProjectFiles()
    val session = remember(files) { ProjectSession(files) }
    MaterialTheme(colorScheme = SpawnKitColors) {
        EditorScreen(session.state, projectSession = session)
    }
}
