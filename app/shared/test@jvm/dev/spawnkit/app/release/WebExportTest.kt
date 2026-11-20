package dev.spawnkit.app.release

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebExportTest {
    @Test
    fun exportedZipIncludesPlayerAssetsAndTheCurrentGameAtTheRoot() =
        runBlocking {
            val wasm = byteArrayOf(0, 97, 115, 109)
            val assets =
                mapOf(
                    "index.html" to "<html>".toByteArray(),
                    "webPlayer.wasm" to wasm,
                    "composeResources/fonts/game.ttf" to byteArrayOf(0, -1, 42),
                )
            val output = ByteArrayOutputStream()
            val game = """{"title":"森"}"""
            writeWebArchive(bundle(assets), game, output)
            val unpacked = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    unpacked[entry.name] = archive.readBytes()
                }
            }
            assertEquals(assets.keys + "game.spawnkit.json", unpacked.keys)
            for ((name, contents) in assets) assertContentEquals(contents, unpacked.getValue(name))
            assertEquals(game, unpacked.getValue("game.spawnkit.json").decodeToString())
        }

    @Test
    fun incompleteBundleCannotProduceAnExport() =
        runBlocking {
            assertFailsWith<IllegalStateException> {
                writeWebArchive(bundle(mapOf("index.html" to byteArrayOf(1))), "{}", ByteArrayOutputStream())
            }
            Unit
        }

    private fun bundle(assets: Map<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, contents) in assets) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }
}
