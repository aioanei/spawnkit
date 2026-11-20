package dev.spawnkit

import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.releaseProblem
import dev.spawnkit.gameplay.StandardEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.util.concurrent.Semaphore
import java.util.zip.ZipInputStream

/** One shared copy of the trusted, CI-built player serves every release. The package comes exclusively from the store. */
internal class WebPlayerBundle(
    open: () -> InputStream?,
) {
    private val entries by lazy {
        open()?.let { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                buildMap {
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) put(entry.name, zip.readBytes())
                        entry = zip.nextEntry
                    }
                }
            }
        }
    }

    fun requireAvailable() {
        if (entries?.containsKey("index.html") != true) {
            throw ArcadeProblem(HttpStatusCode.ServiceUnavailable, "This Arcade needs its web player installed. Contact its operator.")
        }
    }

    fun asset(name: String): ByteArray {
        requireAvailable()
        return entries?.get(name) ?: throw ArcadeProblem(HttpStatusCode.NotFound, "This player file could not be found.")
    }
}

/** Both limits apply to attempts, including invalid bodies. The global window bounds rate-limit state. */
internal class PublishLimits(
    val bytes: Int = GamePackageCodec.MAX_PACKAGE_CHARS,
    private val perAddress: Int = 6,
    private val perServer: Int = 60,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val attempts = ArrayDeque<Pair<Long, String>>()
    val slots = Semaphore(4)

    @Synchronized
    fun allow(address: String): Boolean {
        val now = clockMillis()
        while (attempts.isNotEmpty() && now - attempts.first().first >= 60_000) attempts.removeFirst()
        if (attempts.size >= perServer || attempts.count { it.second == address } >= perAddress) return false
        attempts.addLast(now to address)
        return true
    }
}

internal fun Application.arcade(
    store: ArcadeStore,
    player: WebPlayerBundle,
    limits: PublishLimits = PublishLimits(),
) {
    routing {
        post("/api/games") {
            call.arcadeResponse {
                if (!limits.allow(request.local.remoteAddress)) {
                    response.header(HttpHeaders.RetryAfter, "60")
                    throw ArcadeProblem(HttpStatusCode.TooManyRequests, "Too many publish attempts. Try again in a minute.")
                }
                if (!request.contentType().match(ContentType.Application.Json)) {
                    throw ArcadeProblem(HttpStatusCode.UnsupportedMediaType, "Send a SpawnKit game package as application/json.")
                }
                if (!limits.slots.tryAcquire()) {
                    throw ArcadeProblem(HttpStatusCode.ServiceUnavailable, "This Arcade is busy. Try publishing again shortly.")
                }
                val game =
                    try {
                        val text = readPackage(limits.bytes)
                        withContext(Dispatchers.IO) {
                            player.requireAvailable()
                            val pkg =
                                try {
                                    GamePackageCodec.decode(text)
                                } catch (problem: IllegalArgumentException) {
                                    throw ArcadeProblem(HttpStatusCode.BadRequest, problem.message ?: "This game package is damaged.")
                                }
                            val problem = StandardEngine().let { pkg.releaseProblem(it.runtime, it.host) }
                            if (problem != null) throw ArcadeProblem(HttpStatusCode.UnprocessableEntity, problem)
                            val bytes = GamePackageCodec.encode(pkg).encodeToByteArray()
                            if (bytes.size > limits.bytes) throw tooLarge(limits.bytes)
                            store.publish(pkg.title, bytes)
                        }
                    } finally {
                        limits.slots.release()
                    }
                response.header(HttpHeaders.Location, game.playUrl)
                respondText(game.json().toString(), ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        get("/api/games") {
            call.arcadeResponse {
                val games = withContext(Dispatchers.IO) { store.list() }
                respondText(JsonArray(games.map(PublishedGame::json)).toString(), ContentType.Application.Json)
            }
        }
        get("/api/games/{slug}") {
            call.arcadeResponse {
                val game = withContext(Dispatchers.IO) { store.read(store.find(pathParameters["slug"])) }
                respondBytes(game, ContentType.Application.Json)
            }
        }
        get("/play/{slug}/{asset...}") {
            call.arcadeResponse {
                val slug = pathParameters["slug"]
                val file = withContext(Dispatchers.IO) { store.find(slug) }
                val asset = pathParameters.getAll("asset").orEmpty().joinToString("/")
                when {
                    asset.isEmpty() && !request.path().endsWith("/") -> {
                        respondRedirect("/play/$slug/")
                    }

                    asset == "game.spawnkit.json" -> {
                        respondBytes(withContext(Dispatchers.IO) { store.read(file) }, ContentType.Application.Json)
                    }

                    else -> {
                        val name = asset.ifEmpty { "index.html" }
                        val bytes = withContext(Dispatchers.IO) { player.asset(name) }
                        val type =
                            when (name.substringAfterLast('.')) {
                                "mjs", "js" -> ContentType.parse("text/javascript")
                                "wasm" -> ContentType.parse("application/wasm")
                                else -> ContentType.defaultForFilePath(name)
                            }
                        respondBytes(bytes, type)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.readPackage(maxBytes: Int): String {
    if ((request.contentLength() ?: 0) > maxBytes) throw tooLarge(maxBytes)
    val buffer =
        withTimeoutOrNull(30_000) { receiveChannel().readBuffer(maxBytes.toLong() + 1) }
            ?: throw ArcadeProblem(HttpStatusCode.RequestTimeout, "The upload timed out. Try publishing again.")
    if (buffer.size > maxBytes) throw tooLarge(maxBytes)
    return try {
        buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw ArcadeProblem(HttpStatusCode.BadRequest, "Send the game package as UTF-8 text.")
    }
}

private fun tooLarge(limit: Int) =
    ArcadeProblem(HttpStatusCode.PayloadTooLarge, "This Arcade accepts up to $limit bytes per game. Reduce the game size and try again.")

private suspend fun RoutingCall.arcadeResponse(block: suspend RoutingCall.() -> Unit) {
    try {
        block()
    } catch (problem: ArcadeProblem) {
        respondProblem(problem.status, problem.message ?: "The request failed.")
    } catch (_: IOException) {
        respondProblem(HttpStatusCode.ServiceUnavailable, "Arcade storage is unavailable. Try again or contact its operator.")
    }
}

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String,
) {
    val error = JsonObject(mapOf("error" to JsonPrimitive(detail.take(512))))
    respondText(error.toString(), ContentType.Application.Json, status)
}
