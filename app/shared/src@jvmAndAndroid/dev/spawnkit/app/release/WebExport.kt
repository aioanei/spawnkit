package dev.spawnkit.app.release

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal fun archiveName(title: String): String {
    val stem =
        title
            .removeSuffix(".zip")
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "_")
            .trim()
            .take(80)
    return stem.ifEmpty { "Game" } + ".zip"
}

internal fun openPlayerBundle(): InputStream =
    checkNotNull(ReleasePlatform::class.java.getResourceAsStream("/spawnkit/web-player.zip")) {
        "This builder is missing its web player. Install a builder with the player included and export again."
    }

/** Streams the shipped player and the current release into one ZIP, retaining all relative paths. */
internal suspend fun writeWebArchive(
    bundle: InputStream,
    packageJson: String,
    destination: OutputStream,
) {
    val gameFile = "game.spawnkit.json"
    ZipInputStream(bundle.buffered()).use { input ->
        ZipOutputStream(destination.buffered()).use { output ->
            output.setLevel(Deflater.BEST_SPEED)
            var hasIndex = false
            var hasWasm = false
            var entry = input.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                if (!entry.isDirectory) {
                    val name = entry.name
                    val safePath = name.split('/').none { it.isEmpty() || it == "." || it == ".." }
                    check(name != gameFile && '\\' !in name && ':' !in name && safePath) {
                        "This builder's web player archive is damaged. Reinstall the builder and export again."
                    }
                    output.putNextEntry(ZipEntry(name))
                    val length = input.copyCancellableTo(output)
                    output.closeEntry()
                    if (length > 0 && name == "index.html") hasIndex = true
                    if (length > 0 && name.endsWith(".wasm")) hasWasm = true
                }
                entry = input.nextEntry
            }
            check(hasIndex && hasWasm) { "This builder's web player is incomplete. Reinstall the builder and export again." }
            currentCoroutineContext().ensureActive()
            output.putNextEntry(ZipEntry(gameFile))
            output.write(packageJson.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }
}

internal suspend fun InputStream.copyCancellableTo(output: OutputStream): Long {
    val buffer = ByteArray(64 * 1024)
    val context = currentCoroutineContext()
    var length = 0L
    while (true) {
        context.ensureActive()
        val count = read(buffer)
        if (count < 0) return length
        output.write(buffer, 0, count)
        length += count
    }
}
