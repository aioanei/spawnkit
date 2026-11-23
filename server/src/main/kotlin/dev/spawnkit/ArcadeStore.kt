package dev.spawnkit

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class PublishedGame(
    val slug: String,
    val title: String,
) {
    val playUrl: String get() = "/play/$slug/"

    fun json(): JsonObject =
        buildJsonObject {
            put("slug", JsonPrimitive(slug))
            put("title", JsonPrimitive(title))
            put("playUrl", JsonPrimitive(playUrl))
        }
}

/** Each immutable release is one atomically published file. Its bounded title header is the listing index. */
internal class ArcadeStore(
    private val directory: Path,
    private val maxGames: Int = 128,
) {
    @Synchronized
    fun publish(
        title: String,
        bytes: ByteArray,
    ): PublishedGame {
        Files.createDirectories(directory)
        val full = Files.newDirectoryStream(directory, "*.game").use { files -> files.asSequence().take(maxGames).count() >= maxGames }
        if (full) throw ArcadeProblem(HttpStatusCode.ServiceUnavailable, "This Arcade is full. Contact its operator or use web export.")
        val game = PublishedGame(UUID.randomUUID().toString(), title)
        write(game, bytes)
        return game
    }

    /** Replaces the release at [slug] with [bytes] titled [title], atomically; its play link stays the same. */
    @Synchronized
    fun replace(
        slug: String,
        title: String,
        bytes: ByteArray,
    ): PublishedGame {
        find(slug)
        return PublishedGame(slug, title).also { write(it, bytes) }
    }

    /** Removes the release at [slug]. */
    @Synchronized
    fun delete(slug: String) {
        Files.deleteIfExists(find(slug))
    }

    private fun write(
        game: PublishedGame,
        bytes: ByteArray,
    ) {
        val temp = Files.createTempFile(directory, "release-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temp).buffered()).use {
                it.writeUTF(game.title)
                it.write(bytes)
            }
            Files.move(temp, directory.resolve("${game.slug}.game"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun list(): List<PublishedGame> {
        if (Files.notExists(directory)) return emptyList()
        return Files.newDirectoryStream(directory, "*.game").use { files ->
            files
                .mapNotNull { file ->
                    val slug = file.fileName.toString().removeSuffix(".game")
                    // Damaged or foreign files are skipped, so one bad file never hides every other release.
                    if (!SLUG.matches(slug)) return@mapNotNull null
                    try {
                        PublishedGame(slug, DataInputStream(Files.newInputStream(file).buffered()).use { it.readUTF() })
                    } catch (_: IOException) {
                        null
                    }
                }.sortedWith(compareBy(PublishedGame::title, PublishedGame::slug))
        }
    }

    fun find(slug: String?): Path {
        if (slug == null || !SLUG.matches(slug)) throw missingGame()
        return directory.resolve("$slug.game").takeIf(Files::isRegularFile) ?: throw missingGame()
    }

    fun read(file: Path): ByteArray =
        DataInputStream(Files.newInputStream(file).buffered()).use {
            it.readUTF()
            it.readAllBytes()
        }

    private fun missingGame() = ArcadeProblem(HttpStatusCode.NotFound, "This Arcade game could not be found.")

    private companion object {
        val SLUG = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
