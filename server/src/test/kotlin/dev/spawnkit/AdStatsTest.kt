package dev.spawnkit

import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.AuthResult
import dev.spawnkit.accounts.CapturedMail
import dev.spawnkit.accounts.json
import dev.spawnkit.accounts.testDb
import dev.spawnkit.accounts.testPasswords
import dev.spawnkit.accounts.text
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.Project
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Test ad counts from Arcade plays (C12a): counted once, aggregated per day, readable by the owner only, never earning. */
class AdStatsTest {
    private val db = testDb()
    private val mail = CapturedMail()
    private val accounts = Accounts(db, mail, testPasswords())
    private var now = 1_764_000_000_000L // 2025-11-24 UTC
    private val ads = AdStats(db) { now }
    private val game = GamePackageCodec.encode(GamePackage.of(Project.blank("Ad game")))

    private fun token(email: String): String {
        accounts.register(email, "correct horse", email.substringBefore('@'))
        return assertIs<AuthResult.SignedIn>(accounts.verify(email, mail.lastCode(email))).token
    }

    private fun arcade(
        directory: Path,
        limits: PublishLimits = PublishLimits(bytes = MAX_AD_EVENT_BYTES, perAddress = 30, perServer = 3_000),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { arcade(ArcadeStore(directory), playerFixture(), owners = ArcadeOwners(accounts, OwnedGames(db), ads), adLimits = limits) }
        block()
    }

    private suspend fun HttpClient.report(
        slug: String,
        session: String,
        vararg events: Triple<Int, String, String>,
    ): HttpResponse =
        post("/api/games/$slug/ad-events") {
            contentType(ContentType.Application.Json)
            val list = events.joinToString(",") { (n, placement, kind) -> """{"n":$n,"placement":"$placement","kind":"$kind"}""" }
            setBody("""{"session":"$session","events":[$list]}""")
        }

    private suspend fun HttpClient.stats(
        slug: String,
        token: String,
    ) = Json.parseToJsonElement(get("/api/games/$slug/ad-stats") { bearerAuth(token) }.bodyAsText()).jsonObject

    @Test
    fun eventsAreCountedOncePerSessionAndNumberAndAggregatedByDay() =
        withStore { directory ->
            val ada = token("ada@example.com")
            arcade(directory) {
                val slug = client.publish(game, ada).json().text("slug")
                val view = Triple(1, "banner", "view")
                assertEquals(HttpStatusCode.Accepted, client.report(slug, "play-session-1", view, Triple(2, "banner", "click")).status)
                assertEquals("""{"accepted":0}""", client.report(slug, "play-session-1", view).bodyAsText(), "a retried event is not counted again")
                client.report(slug, "play-session-2", view)
                now += 24 * 60 * 60 * 1000
                client.report(slug, "play-session-3", Triple(1, "interstitial", "view"))

                val stats = client.stats(slug, ada)
                assertEquals(3, stats.getValue("views").jsonPrimitive.long)
                assertEquals(1, stats.getValue("clicks").jsonPrimitive.long)
                assertEquals("0.00", stats.getValue("revenue").jsonPrimitive.content, "test ads never earn")
                assertEquals(true, stats.getValue("testAds").jsonPrimitive.content.toBoolean())
                val days = stats.getValue("days").jsonArray.map { it.jsonObject }
                assertEquals(listOf("2025-11-25", "2025-11-24"), days.map { it.getValue("day").jsonPrimitive.content })
                assertEquals(listOf(1L, 2L), days.map { it.getValue("views").jsonPrimitive.long })
            }
        }

    @Test
    fun onlyTheOwnerReadsTheStatistics() =
        withStore { directory ->
            val ada = token("ada@example.com")
            val bob = token("bob@example.com")
            arcade(directory) {
                val slug = client.publish(game, ada).json().text("slug")
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/games/$slug/ad-stats").status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/games/$slug/ad-stats") { bearerAuth(bob) }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/games/$slug/ad-stats") { bearerAuth(ada) }.status)
            }
        }

    @Test
    fun badReportsUnknownGamesAndFloodsAreRefused() =
        withStore { directory ->
            val ada = token("ada@example.com")
            arcade(directory, PublishLimits(bytes = MAX_AD_EVENT_BYTES, perAddress = 3, perServer = 100)) {
                val slug = client.publish(game, ada).json().text("slug")
                assertEquals(HttpStatusCode.NotFound, client.report("missing", "play-session-1", Triple(1, "banner", "view")).status)
                assertEquals(HttpStatusCode.BadRequest, client.report(slug, "x", Triple(1, "banner", "view")).status, "session IDs are random tokens")
                assertEquals(HttpStatusCode.BadRequest, client.report(slug, "play-session-1", Triple(1, "popup", "view")).status)
                assertEquals(HttpStatusCode.TooManyRequests, client.report(slug, "play-session-1", Triple(1, "banner", "view")).status)
                assertEquals(0, client.stats(slug, ada).getValue("views").jsonPrimitive.long)
            }
        }
}
