package dev.spawnkit

import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.AuthResult
import dev.spawnkit.accounts.CapturedMail
import dev.spawnkit.accounts.json
import dev.spawnkit.accounts.testDb
import dev.spawnkit.accounts.testPasswords
import dev.spawnkit.accounts.text
import dev.spawnkit.data.Games
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.Project
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Owned Arcade games (C11a): signed-in publishing, My games, and owner-only republish and unpublish. */
class OwnedGamesTest {
    private val db = testDb()
    private val mail = CapturedMail()
    private val accounts = Accounts(db, mail, testPasswords())
    private val first = GamePackageCodec.encode(GamePackage.of(Project.blank("First")))
    private val second = GamePackageCodec.encode(GamePackage.of(Project.blank("Second")))

    private fun token(email: String): String {
        accounts.register(email, "correct horse", email.substringBefore('@'))
        return assertIs<AuthResult.SignedIn>(accounts.verify(email, mail.lastCode(email))).token
    }

    private fun arcadeWithAccounts(
        directory: Path,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { arcade(ArcadeStore(directory), playerFixture(), owners = ArcadeOwners(accounts, OwnedGames(db))) }
        block()
    }

    @Test
    fun aSignedInPublishIsOwnedAndListedInMyGames() =
        withStore { directory ->
            val ada = token("ada@example.com")
            arcadeWithAccounts(directory) {
                val owned = client.publish(first, ada)
                assertEquals(HttpStatusCode.Created, owned.status)
                assertEquals(HttpStatusCode.Created, client.publish(second).status, "publishing without signing in still works, unowned")

                val mine = Json.parseToJsonElement(client.get("/api/me/games") { bearerAuth(ada) }.bodyAsText()).jsonArray
                assertEquals(
                    listOf("First"),
                    mine.map {
                        it.jsonObject
                            .getValue("title")
                            .jsonPrimitive.content
                    },
                )
                assertEquals(
                    owned.json().text("slug"),
                    mine
                        .single()
                        .jsonObject
                        .getValue("slug")
                        .jsonPrimitive.content,
                )
                assertEquals(2, Json.parseToJsonElement(client.get("/api/games").bodyAsText()).jsonArray.size)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me/games").status)
            }
        }

    @Test
    fun anUnknownTokenCannotPublish() =
        withStore { directory ->
            arcadeWithAccounts(directory) {
                assertEquals(HttpStatusCode.Unauthorized, client.publish(first, "stale-token").status)
                assertEquals("[]", client.get("/api/games").bodyAsText())
            }
        }

    @Test
    fun onlyTheOwnerRepublishesAndUnpublishes() =
        withStore { directory ->
            val ada = token("ada@example.com")
            val bob = token("bob@example.com")
            arcadeWithAccounts(directory) {
                val slug = client.publish(first, ada).json().text("slug")
                val unowned = client.publish(first).json().text("slug")

                assertEquals(HttpStatusCode.Unauthorized, client.republish(slug, second).status)
                assertEquals(HttpStatusCode.Forbidden, client.republish(slug, second, bob).status)
                assertEquals(HttpStatusCode.Forbidden, client.republish(unowned, second, ada).status)
                assertEquals(HttpStatusCode.NotFound, client.republish("00000000-0000-0000-0000-000000000000", second, ada).status)
                assertEquals(HttpStatusCode.BadRequest, client.republish(slug, "{}", ada).status)
                assertEquals(first, client.get("/api/games/$slug").bodyAsText(), "refused changes leave the game as it was")

                val republished = client.republish(slug, second, ada)
                assertEquals(HttpStatusCode.OK, republished.status)
                assertEquals("Second", republished.json().text("title"))
                assertEquals(second, client.get("/api/games/$slug").bodyAsText())
                assertEquals(second, client.get("/play/$slug/game.spawnkit.json").bodyAsText(), "the play link keeps working")

                assertEquals(HttpStatusCode.Forbidden, client.delete("/api/games/$slug") { bearerAuth(bob) }.status)
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/games/$slug") { bearerAuth(ada) }.status)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/games/$slug").status)
                assertEquals("[]", client.get("/api/me/games") { bearerAuth(ada) }.bodyAsText())
            }
        }

    @Test
    fun gamesPublishedBeforeAccountsAreImportedAsUnowned() =
        withStore { directory ->
            val old = ArcadeStore(directory).publish("Old", first.encodeToByteArray())
            val ada = token("ada@example.com")
            arcadeWithAccounts(directory) {
                // The test host starts the application on its first request.
                assertEquals(HttpStatusCode.Forbidden, client.republish(old.slug, second, ada).status)
                assertEquals(listOf<Long?>(null), db.tx { Games.selectAll().map { it[Games.owner] } })
                assertEquals(first, client.get("/api/games/${old.slug}").bodyAsText())
            }
        }

    @Test
    fun aDeletedAccountsGamesStayPlayable() =
        withStore { directory ->
            val ada = token("ada@example.com")
            arcadeWithAccounts(directory) {
                val slug = client.publish(first, ada).json().text("slug")
                assertTrue(accounts.delete(ada))
                assertEquals(first, client.get("/play/$slug/game.spawnkit.json").bodyAsText())
                assertEquals(1, Json.parseToJsonElement(client.get("/api/games").bodyAsText()).jsonArray.size)
            }
        }
}
