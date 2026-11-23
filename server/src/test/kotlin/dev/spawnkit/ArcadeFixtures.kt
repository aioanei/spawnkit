package dev.spawnkit

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Publishes [text] as a game package, signed in with [token] when given. */
internal suspend fun HttpClient.publish(
    text: String,
    token: String? = null,
) = post("/api/games") {
    contentType(ContentType.Application.Json)
    token?.let { bearerAuth(it) }
    setBody(text)
}

/** Republishes [slug] with [text], signed in with [token] when given. */
internal suspend fun HttpClient.republish(
    slug: String,
    text: String,
    token: String? = null,
) = put("/api/games/$slug") {
    contentType(ContentType.Application.Json)
    token?.let { bearerAuth(it) }
    setBody(text)
}

/** A tiny stand-in for the CI-built web player. */
internal fun playerFixture(): WebPlayerBundle {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, data) in mapOf("index.html" to "index", "nested/player.mjs" to "script", "player.wasm" to "wasm")) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data.encodeToByteArray())
            zip.closeEntry()
        }
    }
    return WebPlayerBundle { bytes.toByteArray().inputStream() }
}

/** Runs [block] with a temporary Arcade directory. */
internal fun withStore(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("spawnkit-arcade-test")
    try {
        block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
