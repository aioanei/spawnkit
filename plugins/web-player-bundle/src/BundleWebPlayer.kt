package dev.spawnkit.build.release

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packages exactly the universal player's distribution, preserving the relative URLs used by its HTML and modules. */
@TaskAction
fun bundleWebPlayer(
    @Input(inferTaskDependency = false) player: Path,
    @Output output: Path,
) {
    val root = player.toFile()
    for (required in listOf("index.html", "webPlayer.mjs", "webPlayer.wasm", "skiko.wasm")) {
        check(root.resolve(required).isFile) { "Missing $required from the web player. Run ./kotlin build -m webPlayer -v release first." }
    }
    val destination = output.resolve("spawnkit/web-player.zip").toFile()
    destination.parentFile.mkdirs()
    ZipOutputStream(destination.outputStream().buffered()).use { zip ->
        for (file in root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            check(name != "game.spawnkit.json") { "The universal player must be built without a game package." }
            zip.putNextEntry(ZipEntry(name).apply { time = 0L })
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
