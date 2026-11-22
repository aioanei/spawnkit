package dev.spawnkit

import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.CapturedMail
import dev.spawnkit.accounts.authRoutes
import dev.spawnkit.accounts.testDb
import dev.spawnkit.accounts.testPasswords
import dev.spawnkit.ai.AccountClient
import dev.spawnkit.ai.AccountException
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.Project
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The builder's account client (C11b) against the real C11a routes on the Ktor test host. */
class AccountClientTest {
    private val db = testDb()
    private val mail = CapturedMail()
    private val accounts = Accounts(db, mail, testPasswords())
    private val first = GamePackageCodec.encode(GamePackage.of(Project.blank("First")))
    private val second = GamePackageCodec.encode(GamePackage.of(Project.blank("Second")))

    private fun server(
        module: Application.() -> Unit = {
            authRoutes(accounts)
            arcade(ArcadeStore(java.nio.file.Files.createTempDirectory("client-arcade")), playerFixture(), owners = ArcadeOwners(accounts, OwnedGames(db)))
        },
        block: suspend ApplicationTestBuilder.(AccountClient) -> Unit,
    ) = testApplication {
        application(module)
        block(AccountClient("", client))
    }

    @Test
    fun registerVerifySignInAndReadMe() =
        server { api ->
            assertContains(api.register("ada@example.com", "correct horse", "Ada"), "code")
            val verified = api.verify("ada@example.com", mail.lastCode("ada@example.com"))
            assertEquals("Ada", verified.account.displayName)
            assertFalse(verified.token in verified.toString(), "the token never appears in logs")

            val session = api.signIn("ada@example.com", "correct horse")

            assertEquals(verified.account, api.me(session.token))
            api.signOut(session.token)
            assertEquals(401, assertFailsWith<AccountException> { api.me(session.token) }.status)
        }

    @Test
    fun resetAndDelete() =
        server { api ->
            api.register("ada@example.com", "correct horse", "Ada")
            api.verify("ada@example.com", mail.lastCode("ada@example.com"))
            assertContains(api.requestReset("ada@example.com"), "code")

            val reset = api.reset("ada@example.com", mail.lastCode("ada@example.com"), "brand new pass")
            api.deleteAccount(reset.token)

            assertEquals(401, assertFailsWith<AccountException> { api.signIn("ada@example.com", "brand new pass") }.status)
        }

    @Test
    fun publishListRepublishAndUnpublishAsTheOwner() =
        server { api ->
            api.register("ada@example.com", "correct horse", "Ada")
            val token = api.verify("ada@example.com", mail.lastCode("ada@example.com")).token

            val game = api.publish(token, first)
            assertEquals("First", game.title)
            assertTrue(game.playUrl.endsWith("/play/${game.slug}/"))
            assertEquals(listOf(game), api.myGames(token))

            val updated = api.republish(token, game.slug, second)
            assertEquals(game.copy(title = "Second"), updated)

            api.unpublish(token, game.slug)
            assertEquals(emptyList(), api.myGames(token))
            assertEquals("First", api.publish(null, first).title, "signed-out publishing still works")
        }

    @Test
    fun refusalsCarryTheServerMessage() =
        server { api ->
            val wrong = assertFailsWith<AccountException> { api.signIn("ada@example.com", "wrong password") }
            assertEquals(401, wrong.status)
            assertContains(wrong.message.orEmpty(), "not right")
            val invalid = assertFailsWith<AccountException> { api.register("nope", "correct horse", "Ada") }
            assertEquals(400, invalid.status)
            assertContains(invalid.message.orEmpty(), "valid email")
        }

    @Test
    fun statusTellsWhetherEmailAndAiAreAvailable() =
        server(module = { spawnkit(ServerConfig.fromEnvironment(mapOf("SPAWNKIT_DATA_DIR" to testDataDir(), "SPAWNKIT_LOCAL" to "1"))) }) { api ->
            val status = api.status()
            assertTrue(status.emailSignIn)
            assertFalse(status.aiAvailable)
            assertContains(status.ai, "not set up")
        }

    @Test
    fun anUnreachableServerExplainsItself() =
        server { _ ->
            val offline = AccountClient("http://127.0.0.1:1", io.ktor.client.HttpClient())
            val problem = assertFailsWith<AccountException> { offline.status() }
            assertContains(problem.message.orEmpty(), "reach")
        }

    private fun testDataDir(): String =
        java.nio.file.Files
            .createTempDirectory(java.nio.file.Files.createDirectories(java.nio.file.Path.of("build")), "client-")
            .toString()
}
