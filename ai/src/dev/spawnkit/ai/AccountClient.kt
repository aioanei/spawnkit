package dev.spawnkit.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A SpawnKit server refused a request or could not be reached; [message] is a plain explanation for the creator. */
public class AccountException(
    message: String,
    /** The HTTP status, or 0 when the server could not be reached. */
    public val status: Int,
) : Exception(message)

/** What a signed-in creator knows about their account. */
public data class AccountProfile(
    /** Account number. */
    public val id: Long,
    /** Name shown to others. */
    public val displayName: String,
    /** Email address, when the account has one. */
    public val email: String?,
    /** Plan, `free` for now (ADR-0011). */
    public val plan: String,
)

/** A signed-in session. [token] is a credential: keep it in secure storage only. */
public class AccountSession(
    /** The session token, sent as a bearer token. */
    public val token: String,
    /** The account it signs in. */
    public val account: AccountProfile,
) {
    override fun toString(): String = "AccountSession(account=$account)"
}

/** What a SpawnKit server offers. */
public data class ServerStatus(
    /** Whether email sign-in works on this server. */
    public val emailSignIn: Boolean,
    /** Whether SpawnKit AI works on this server. */
    public val aiAvailable: Boolean,
    /** The server's sentence about AI, for the creator. */
    public val ai: String,
)

/** A game published to a SpawnKit Arcade. */
public data class ArcadeGame(
    /** The game's ID on the Arcade. */
    public val slug: String,
    /** Its title. */
    public val title: String,
    /** The full play link. */
    public val playUrl: String,
)

/** Test ad views and clicks of one game on one UTC day (C12). */
public data class AdDayCount(
    /** The day, `YYYY-MM-DD`. */
    public val day: String,
    /** Ads seen. */
    public val views: Long,
    /** Ads tapped. */
    public val clicks: Long,
)

/** A game's test ad statistics from Arcade plays (C12). Revenue is always zero: test ads earn nothing. */
public data class AdStatsReport(
    /** Ads seen in all. */
    public val views: Long,
    /** Ads tapped in all. */
    public val clicks: Long,
    /** Counts per day, newest first. */
    public val days: List<AdDayCount>,
    /** Test purchases in all; no money was charged. */
    public val purchases: Long = 0,
    /** Test purchases per day and product, newest day first. */
    public val purchaseDays: List<PurchaseCount> = emptyList(),
)

/** Test purchases of one product on one UTC day (C12). */
public data class PurchaseCount(
    /** The day, `YYYY-MM-DD`. */
    public val day: String,
    /** What was bought. */
    public val product: String,
    /** How many times. */
    public val count: Long,
)

/** A SpawnKit server's accounts and Arcade, as the builder uses them (C11b). [AccountClient] is the real one. */
public interface AccountService {
    /** What the server offers. */
    public suspend fun status(): ServerStatus

    /** Starts an account; a code is emailed. Returns what to tell the creator. */
    public suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): String

    /** Finishes sign-up with the emailed [code]. */
    public suspend fun verify(
        email: String,
        code: String,
    ): AccountSession

    /** Signs in. */
    public suspend fun signIn(
        email: String,
        password: String,
    ): AccountSession

    /** Ends the session of [token]. */
    public suspend fun signOut(token: String)

    /** Asks for a reset code. Returns what to tell the creator. */
    public suspend fun requestReset(email: String): String

    /** Sets a new password with the emailed [code]; other sessions end. */
    public suspend fun reset(
        email: String,
        code: String,
        newPassword: String,
    ): AccountSession

    /** The account signed in with [token]. */
    public suspend fun me(token: String): AccountProfile

    /** Deletes the account signed in with [token]. Its published games stay playable, unowned. */
    public suspend fun deleteAccount(token: String)

    /** Games the account of [token] published. */
    public suspend fun myGames(token: String): List<ArcadeGame>

    /** Publishes [packageJson], owned by the account of [token], or unowned without one. */
    public suspend fun publish(
        token: String?,
        packageJson: String,
    ): ArcadeGame

    /** Replaces the owned game [slug] with [packageJson]; its play link stays the same. */
    public suspend fun republish(
        token: String,
        slug: String,
        packageJson: String,
    ): ArcadeGame

    /** Unpublishes the owned game [slug]. */
    public suspend fun unpublish(
        token: String,
        slug: String,
    )

    /** Test ad statistics of the owned game [slug] (C12). */
    public suspend fun adStats(
        token: String,
        slug: String,
    ): AdStatsReport
}

/**
 * The builder's client for a SpawnKit server's accounts and Arcade (C11b, ADR-0010): sign-up with an emailed code,
 * sign-in, reset, sign-out, deletion, and publishing as the owner with My games. Refusals and connection problems
 * become [AccountException]s with a plain message. Tokens are passed in and never stored here.
 */
public class AccountClient(
    baseUrl: String,
    private val http: HttpClient,
) : AccountService {
    private val base = baseUrl.trim().trimEnd('/')

    override suspend fun status(): ServerStatus {
        val json = call(HttpMethod.Get, "/api/server").obj()
        return ServerStatus(json.bool("emailSignIn"), json.bool("aiAvailable"), json.text("ai"))
    }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ): String =
        call(
            HttpMethod.Post,
            "/api/auth/register",
            body("email" to email, "password" to password, "displayName" to displayName),
        ).obj().text("message")

    override suspend fun verify(
        email: String,
        code: String,
    ): AccountSession = session(call(HttpMethod.Post, "/api/auth/verify", body("email" to email, "code" to code)))

    override suspend fun signIn(
        email: String,
        password: String,
    ): AccountSession = session(call(HttpMethod.Post, "/api/auth/sign-in", body("email" to email, "password" to password)))

    override suspend fun signOut(token: String) {
        call(HttpMethod.Post, "/api/auth/sign-out", token = token)
    }

    override suspend fun requestReset(email: String): String =
        call(HttpMethod.Post, "/api/auth/reset", body("email" to email)).obj().text("message")

    override suspend fun reset(
        email: String,
        code: String,
        newPassword: String,
    ): AccountSession =
        session(
            call(
                HttpMethod.Post,
                "/api/auth/reset/confirm",
                body(
                    "email" to email,
                    "code" to code,
                    "password" to newPassword,
                ),
            ),
        )

    override suspend fun me(token: String): AccountProfile = profile(call(HttpMethod.Get, "/api/me", token = token).obj())

    override suspend fun deleteAccount(token: String) {
        call(HttpMethod.Delete, "/api/me", token = token)
    }

    override suspend fun myGames(token: String): List<ArcadeGame> =
        (call(HttpMethod.Get, "/api/me/games", token = token) as? JsonArray ?: unreadable()).map { game(it.obj()) }

    override suspend fun publish(
        token: String?,
        packageJson: String,
    ): ArcadeGame = game(call(HttpMethod.Post, "/api/games", packageJson, token).obj())

    override suspend fun republish(
        token: String,
        slug: String,
        packageJson: String,
    ): ArcadeGame = game(call(HttpMethod.Put, "/api/games/${checkedSlug(slug)}", packageJson, token).obj())

    override suspend fun unpublish(
        token: String,
        slug: String,
    ) {
        call(HttpMethod.Delete, "/api/games/${checkedSlug(slug)}", token = token)
    }

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = null,
    ): JsonElement? {
        val response: HttpResponse
        val text: String
        try {
            response =
                http.request("$base$path") {
                    this.method = method
                    token?.let { bearerAuth(it) }
                    body?.let { json(it) }
                }
            text = response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AccountException("Unable to reach the SpawnKit server. Check its address and your connection.", 0)
        }
        val json = if (text.isBlank()) null else runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (!response.status.isSuccess()) {
            val message = ((json as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull?.take(MAX_MESSAGE)
            throw AccountException(
                message ?: "The SpawnKit server could not do that (HTTP ${response.status.value}).",
                response.status.value,
            )
        }
        return json
    }

    private fun HttpRequestBuilder.json(text: String) {
        contentType(ContentType.Application.Json)
        setBody(text)
    }

    private fun body(vararg fields: Pair<String, String>): String =
        JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    private fun session(json: JsonElement?): AccountSession {
        val root = json.obj()
        return AccountSession(root.text("token"), profile(root["account"].obj()))
    }

    private fun profile(json: JsonObject): AccountProfile =
        AccountProfile(
            (json["id"] as? JsonPrimitive)?.longOrNull ?: unreadable(),
            json.text("displayName"),
            (json["email"] as? JsonPrimitive)?.contentOrNull,
            json.text("plan"),
        )

    override suspend fun adStats(
        token: String,
        slug: String,
    ): AdStatsReport {
        val json = call(HttpMethod.Get, "/api/games/${checkedSlug(slug)}/ad-stats", token = token).obj()
        // Test ads earn nothing; a server claiming otherwise is not one this builder understands.
        if (json.text("revenue") != "0.00") unreadable()
        val days =
            (json["days"] as? JsonArray ?: unreadable()).map {
                val day = it.obj()
                AdDayCount(day.text("day"), day.whole("views"), day.whole("clicks"))
            }
        val bought =
            (json["purchaseDays"] as? JsonArray ?: unreadable()).map {
                val item = it.obj()
                PurchaseCount(item.text("day"), item.text("product"), item.whole("count"))
            }
        return AdStatsReport(json.whole("views"), json.whole("clicks"), days, json.whole("purchases"), bought)
    }

    private fun JsonObject.whole(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: unreadable()

    private fun game(json: JsonObject): ArcadeGame {
        val path = json.text("playUrl")
        if (!PLAY_PATH.matches(path)) unreadable()
        return ArcadeGame(json.text("slug"), json.text("title"), base + path)
    }

    private fun checkedSlug(slug: String): String {
        require(SLUG.matches(slug)) { "That is not an Arcade game ID" }
        return slug
    }

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: unreadable()

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: unreadable()

    private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: unreadable()

    private fun unreadable(): Nothing =
        throw AccountException("The SpawnKit server sent an answer this version cannot read. Update SpawnKit.", 0)

    private companion object {
        const val MAX_MESSAGE = 400
        val SLUG = Regex("[0-9a-zA-Z-]{1,64}")
        val PLAY_PATH = Regex("/play/[0-9a-zA-Z_-]+/")
    }
}
