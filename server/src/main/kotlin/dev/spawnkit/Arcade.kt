package dev.spawnkit

import dev.spawnkit.accounts.optionalAccount
import dev.spawnkit.accounts.signedIn
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.releaseProblem
import dev.spawnkit.gameplay.StandardEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.Application
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.io.InputStream
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

/**
 * Largest accepted upload in UTF-8 bytes. Packages are limited in UTF-16 characters, and one character can take up to
 * three UTF-8 bytes, so every package the builder can produce fits. The decoder still enforces the character limit.
 */
internal const val MAX_UPLOAD_BYTES: Int = 3 * GamePackageCodec.MAX_PACKAGE_CHARS

/** Both limits apply to attempts, including invalid bodies. The global window bounds rate-limit state. */
internal class PublishLimits(
    val bytes: Int = MAX_UPLOAD_BYTES,
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

/**
 * The Arcade: publish, list, fetch and play games. With [owners] (a server with a database), a game published while
 * signed in belongs to that account, which alone can republish or unpublish it, and existing game files are indexed
 * as unowned at startup.
 */
internal fun Application.arcade(
    store: ArcadeStore,
    player: WebPlayerBundle,
    limits: PublishLimits = PublishLimits(),
    owners: ArcadeOwners? = null,
    adLimits: PublishLimits = PublishLimits(bytes = MAX_AD_EVENT_BYTES, perAddress = 30, perServer = 3_000),
) {
    owners?.games?.importUnowned(store.list())
    routing {
        post("/api/games") {
            call.arcadeResponse {
                val owner = owners?.let { optionalAccount(it.accounts) }
                val game =
                    receiveGame(player, limits) { pkg, bytes ->
                        store.publish(pkg.title, bytes).also { owners?.games?.record(it, owner?.id) }
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
        if (owners != null) ownerRoutes(store, player, limits, owners)
        if (owners?.ads != null) adRoutes(store, owners, owners.ads, adLimits)
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

/** My games, plus republishing and unpublishing, which only the owner of a game may do. */
private fun Route.ownerRoutes(
    store: ArcadeStore,
    player: WebPlayerBundle,
    limits: PublishLimits,
    owners: ArcadeOwners,
) {
    get("/api/me/games") {
        call.arcadeResponse {
            val account = signedIn(owners.accounts).second
            val games = withContext(Dispatchers.IO) { owners.games.ownedBy(account.id) }
            respondText(JsonArray(games.map(PublishedGame::json)).toString(), ContentType.Application.Json)
        }
    }
    put("/api/games/{slug}") {
        call.arcadeResponse {
            val slug = requireOwned(store, owners)
            val game =
                receiveGame(player, limits) { pkg, bytes ->
                    store.replace(slug, pkg.title, bytes).also { owners.games.republished(slug, pkg.title) }
                }
            respondText(game.json().toString(), ContentType.Application.Json)
        }
    }
    delete("/api/games/{slug}") {
        call.arcadeResponse {
            val slug = requireOwned(store, owners)
            withContext(Dispatchers.IO) {
                store.delete(slug)
                owners.games.removed(slug)
            }
            respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Test ad counts (C12). Players report views and clicks without signing in: a random play-session ID and numbered
 * events, no personal data, rate-limited per address. Only the game's owner reads the statistics, and revenue is
 * always zero because test ads earn nothing.
 */
private fun Route.adRoutes(
    store: ArcadeStore,
    owners: ArcadeOwners,
    ads: AdStats,
    limits: PublishLimits,
) {
    post("/api/games/{slug}/ad-events") {
        call.arcadeResponse {
            if (!limits.allow(request.local.remoteAddress)) {
                response.header(HttpHeaders.RetryAfter, "60")
                throw ArcadeProblem(HttpStatusCode.TooManyRequests, "Too many ad reports. Try again in a minute.")
            }
            val file = withContext(Dispatchers.IO) { store.find(pathParameters["slug"]) }
            val slug = file.fileName.toString().removeSuffix(".game")
            val text = readLimitedText(limits.bytes) { ArcadeProblem(HttpStatusCode.PayloadTooLarge, "Send at most 50 ad events at once.") }
            val (session, events) = parseAdEvents(text)
            val accepted = withContext(Dispatchers.IO) { ads.record(slug, session, events) }
            respondText(
                JsonObject(mapOf("accepted" to JsonPrimitive(accepted))).toString(),
                ContentType.Application.Json,
                HttpStatusCode.Accepted,
            )
        }
    }
    get("/api/games/{slug}/ad-stats") {
        call.arcadeResponse {
            val slug = requireOwned(store, owners)
            val days = withContext(Dispatchers.IO) { ads.daily(slug) }
            val body =
                JsonObject(
                    mapOf(
                        "testAds" to JsonPrimitive(true),
                        "views" to JsonPrimitive(days.sumOf { it.views }),
                        "clicks" to JsonPrimitive(days.sumOf { it.clicks }),
                        "revenue" to JsonPrimitive("0.00"),
                        "days" to
                            JsonArray(
                                days.map {
                                    JsonObject(
                                        mapOf(
                                            "day" to JsonPrimitive(it.day),
                                            "views" to JsonPrimitive(it.views),
                                            "clicks" to JsonPrimitive(it.clicks),
                                        ),
                                    )
                                },
                            ),
                    ),
                )
            respondText(body.toString(), ContentType.Application.Json)
        }
    }
}

/** The play session and events of an ad report, or a 400 problem explaining what is wrong. */
private fun parseAdEvents(text: String): Pair<String, List<AdEventReport>> {
    fun bad(): Nothing =
        throw ArcadeProblem(HttpStatusCode.BadRequest, "Send {\"session\": id, \"events\": [{\"n\", \"placement\", \"kind\"}]}.")
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: bad()
    val session = (root["session"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad()
    if (!session.matches(SESSION_ID)) bad()
    val events = (root["events"] as? JsonArray) ?: bad()
    if (events.size > MAX_AD_EVENTS) bad()
    return session to
        events.map { element ->
            val event = element as? JsonObject ?: bad()
            val number = (event["n"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: bad()
            val placement = (event["placement"] as? JsonPrimitive)?.content ?: bad()
            val kind = (event["kind"] as? JsonPrimitive)?.content ?: bad()
            if (number !in 0..MAX_EVENT_NUMBER || placement !in setOf("banner", "interstitial") || kind !in setOf("view", "click")) bad()
            AdEventReport(number, placement, kind == "click")
        }
}

private val SESSION_ID = Regex("[A-Za-z0-9-]{8,64}")
private const val MAX_AD_EVENTS = 50
private const val MAX_EVENT_NUMBER = 100_000
internal const val MAX_AD_EVENT_BYTES = 8 * 1024

/** The slug of an existing game the signed-in account owns: 401 when signed out, 404 when missing, 403 otherwise. */
private suspend fun RoutingCall.requireOwned(
    store: ArcadeStore,
    owners: ArcadeOwners,
): String {
    val account = signedIn(owners.accounts).second
    val file = withContext(Dispatchers.IO) { store.find(pathParameters["slug"]) }
    val slug = file.fileName.toString().removeSuffix(".game")
    val owner = withContext(Dispatchers.IO) { owners.games.owner(slug) }
    if (owner != account.id) throw ArcadeProblem(HttpStatusCode.Forbidden, "Only the creator who published this game can change it.")
    return slug
}

/**
 * Reads, validates and stores an uploaded game package: rate limit, media type, size, decoding and the release check,
 * then [save] with the package and its re-encoded bytes, on the IO dispatcher.
 */
private suspend fun RoutingCall.receiveGame(
    player: WebPlayerBundle,
    limits: PublishLimits,
    save: (GamePackage, ByteArray) -> PublishedGame,
): PublishedGame {
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
    try {
        val text = readLimitedText(limits.bytes) { tooLarge(limits.bytes) }
        return withContext(Dispatchers.IO) {
            player.requireAvailable()
            val pkg =
                try {
                    GamePackageCodec.decode(text)
                } catch (problem: IllegalArgumentException) {
                    throw ArcadeProblem(HttpStatusCode.BadRequest, problem.message ?: "This game package is damaged.")
                }
            val problem = StandardEngine().let { pkg.releaseProblem(it.runtime, it.host) }
            if (problem != null) throw ArcadeProblem(HttpStatusCode.UnprocessableEntity, problem)
            // Re-encoding adds the current envelope fields, so a package at the limit can outgrow it.
            val bytes =
                try {
                    GamePackageCodec.encode(pkg).encodeToByteArray()
                } catch (_: IllegalArgumentException) {
                    throw tooLarge(limits.bytes)
                }
            if (bytes.size > limits.bytes) throw tooLarge(limits.bytes)
            save(pkg, bytes)
        }
    } finally {
        limits.slots.release()
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
