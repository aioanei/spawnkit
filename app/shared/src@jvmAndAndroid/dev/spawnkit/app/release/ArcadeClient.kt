package dev.spawnkit.app.release

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

private const val ADDRESS_HELP = "Enter the Arcade's HTTPS address, or use http://localhost for a local Arcade."

internal suspend fun publishGame(
    address: String,
    packageJson: String,
): String =
    withContext(Dispatchers.IO) {
        val base = runCatching { URI(address.trim()) }.getOrNull()
        require(
            base != null && base.host != null && base.userInfo == null && base.rawQuery == null && base.rawFragment == null &&
                (base.path.isNullOrEmpty() || base.path == "/") &&
                (
                    base.scheme.equals("https", ignoreCase = true) ||
                        (base.scheme.equals("http", ignoreCase = true) && base.host in setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2"))
                ),
        ) { ADDRESS_HELP }
        val connection = base.resolve("/api/games").toURL().openConnection() as HttpURLConnection
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            if (continuation.isActive) {
                continuation.resumeWith(
                    runCatching {
                        try {
                            sendGame(connection, packageJson, base)
                        } catch (_: IOException) {
                            error("Unable to reach the Arcade. Check its address and your connection, then try again.")
                        } finally {
                            connection.disconnect()
                        }
                    },
                )
            }
        }
    }

private fun sendGame(
    connection: HttpURLConnection,
    packageJson: String,
    base: URI,
): String {
    val bytes = packageJson.toByteArray(Charsets.UTF_8)
    connection.apply {
        requestMethod = "POST"
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        doOutput = true
        setFixedLengthStreamingMode(bytes.size)
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
    }
    connection.outputStream.use { it.write(bytes) }
    val status = connection.responseCode
    val stream = if (status == HttpURLConnection.HTTP_CREATED) connection.inputStream else connection.errorStream
    val response =
        stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                check(text.length + read <= 8192) { "The Arcade returned an unreadable response. Check its address." }
                text.append(buffer, 0, read)
            }
            runCatching { Json.parseToJsonElement(text.toString()) as? JsonObject }.getOrNull()
        }
    check(status == HttpURLConnection.HTTP_CREATED) {
        (response?.get("error") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.take(400)
            ?.takeIf { it.isNotBlank() }
            ?: "The Arcade could not publish the game (HTTP $status). Check the server and try again."
    }
    val path = (response?.get("playUrl") as? JsonPrimitive)?.takeIf { it.isString }?.content
    check(path != null && Regex("/play/[a-zA-Z0-9_-]+/").matches(path)) {
        "The Arcade did not return a valid play link. Check the game's listing on the Arcade."
    }
    return base.resolve(path).toASCIIString()
}
