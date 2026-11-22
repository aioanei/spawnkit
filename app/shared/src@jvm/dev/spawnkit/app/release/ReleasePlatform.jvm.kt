package dev.spawnkit.app.release

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Composable
actual fun rememberReleasePlatform(): ReleasePlatform = DesktopReleasePlatform

private object DesktopReleasePlatform : ReleasePlatform {
    override val available = true

    override suspend fun exportWeb(
        suggestedName: String,
        packageJson: String,
    ): Boolean {
        val path = chooseArchive(archiveName(suggestedName)) ?: return false
        withContext(Dispatchers.IO) {
            val temp = Files.createTempFile(path.toAbsolutePath().parent, ".spawnkit-web-", ".tmp")
            try {
                openPlayerBundle().use { bundle ->
                    Files.newOutputStream(temp).use { writeWebArchive(bundle, packageJson, it) }
                }
                currentCoroutineContext().ensureActive()
                try {
                    Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, path, REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
        return true
    }

    override suspend fun publish(
        arcadeUrl: String,
        packageJson: String,
    ): String = publishGame(arcadeUrl, packageJson)
}

private suspend fun chooseArchive(name: String): Path? =
    suspendCancellableCoroutine { result ->
        EventQueue.invokeLater {
            if (!result.isActive) return@invokeLater
            result.resumeWith(
                runCatching {
                    val dialog = FileDialog(null as Frame?, "Export web game", FileDialog.SAVE)
                    result.invokeOnCancellation { EventQueue.invokeLater(dialog::dispose) }
                    try {
                        dialog.file = name
                        dialog.isVisible = true
                        dialog.files.firstOrNull()?.toPath()
                    } finally {
                        dialog.dispose()
                    }
                },
            )
        }
    }
