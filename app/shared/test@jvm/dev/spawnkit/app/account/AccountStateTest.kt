package dev.spawnkit.app.account

import dev.spawnkit.ai.AccountException
import dev.spawnkit.ai.AccountProfile
import dev.spawnkit.ai.AccountService
import dev.spawnkit.ai.AccountSession
import dev.spawnkit.ai.ArcadeGame
import dev.spawnkit.ai.ServerStatus
import dev.spawnkit.app.assistant.MemorySecretStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in to a SpawnKit server from the builder (C11b), with a fake server. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountStateTest {
    private val secrets = MemorySecretStore()
    private val server = FakeAccountService()
    private val addresses = mutableListOf<String>()

    private fun TestScope.accounts(): AccountState =
        AccountState(secrets, this, service = { address ->
            addresses += address
            server
        })

    @Test
    fun signingInKeepsTheTokenOnlyInSecureStorage() =
        runTest {
            val accounts = accounts()
            accounts.address = "https://arcade.example.org"
            accounts.signIn("ada@example.com", "correct horse")
            advanceUntilIdle()

            assertEquals("Ada", accounts.account?.displayName)
            assertEquals(FakeAccountService.TOKEN, secrets.read(AccountState.TOKEN_NAME))
            assertEquals("https://arcade.example.org", secrets.read(AccountState.SERVER_NAME))
            assertFalse(FakeAccountService.TOKEN in accounts.toString())
            assertNull(accounts.notice)
            assertEquals(listOf("https://arcade.example.org"), addresses.distinct())
        }

    @Test
    fun registeringAsksForTheCodeThenSignsIn() =
        runTest {
            val accounts = accounts()
            accounts.address = "http://10.0.2.2:8080"
            accounts.register("ada@example.com", "correct horse", "Ada")
            advanceUntilIdle()
            assertEquals("ada@example.com", accounts.awaitingCode)
            assertContains(accounts.notice.orEmpty(), "code")

            accounts.verify("123456")
            advanceUntilIdle()

            assertTrue(accounts.signedIn)
            assertNull(accounts.awaitingCode)
        }

    @Test
    fun signingOutForgetsTheToken() =
        runTest {
            val accounts = accounts()
            accounts.address = "https://arcade.example.org"
            accounts.signIn("ada@example.com", "correct horse")
            advanceUntilIdle()

            accounts.signOut()
            advanceUntilIdle()

            assertFalse(accounts.signedIn)
            assertNull(secrets.read(AccountState.TOKEN_NAME))
            assertEquals(listOf(FakeAccountService.TOKEN), server.signedOut)
        }

    @Test
    fun aRevokedTokenSignsOutWhenTheBuilderStarts() =
        runTest {
            secrets.write(AccountState.SERVER_NAME, "https://arcade.example.org")
            secrets.write(AccountState.TOKEN_NAME, "revoked")
            server.meFailure = AccountException("Sign in to do this. Your session may have ended.", 401)
            val accounts = accounts()

            accounts.start()
            advanceUntilIdle()

            assertFalse(accounts.signedIn)
            assertNull(secrets.read(AccountState.TOKEN_NAME))
            assertContains(accounts.notice.orEmpty(), "session ended")
        }

    @Test
    fun aSavedSessionIsRestored() =
        runTest {
            secrets.write(AccountState.SERVER_NAME, "https://arcade.example.org")
            secrets.write(AccountState.TOKEN_NAME, FakeAccountService.TOKEN)
            val accounts = accounts()

            accounts.start()
            advanceUntilIdle()

            assertEquals("Ada", accounts.account?.displayName)
            assertTrue(accounts.status?.aiAvailable == true)
        }

    @Test
    fun refusalsBecomePlainNotices() =
        runTest {
            server.signInFailure = AccountException("Email or password is not right.", 401)
            val accounts = accounts()
            accounts.address = "https://arcade.example.org"

            accounts.signIn("ada@example.com", "wrong password")
            advanceUntilIdle()

            assertFalse(accounts.signedIn)
            assertEquals("Email or password is not right.", accounts.notice)
            assertFalse(accounts.busy)
        }

    @Test
    fun onlySecureOrLocalAddressesAreUsed() =
        runTest {
            val accounts = accounts()
            accounts.address = "http://arcade.example.org"
            accounts.checkServer()
            advanceUntilIdle()
            assertContains(accounts.notice.orEmpty(), "HTTPS")
            assertTrue(addresses.isEmpty(), "nothing is sent to an insecure address")

            accounts.address = "https://arcade.example.org/"
            accounts.checkServer()
            advanceUntilIdle()
            assertNotNull(accounts.status)
            assertEquals("https://arcade.example.org", addresses.single())
        }

    @Test
    fun aRevokedSessionDuringPublishingSignsOut() =
        runTest {
            val accounts = accounts()
            accounts.address = "https://arcade.example.org"
            accounts.signIn("ada@example.com", "correct horse")
            advanceUntilIdle()
            server.publishFailure = AccountException("Sign in to do this. Your session may have ended.", 401)

            val problem = runCatching { accounts.publish("{}") }.exceptionOrNull()

            assertContains(problem?.message.orEmpty(), "session ended")
            assertFalse(accounts.signedIn)
        }

    @Test
    fun deletingTheAccountSignsOut() =
        runTest {
            val accounts = accounts()
            accounts.address = "https://arcade.example.org"
            accounts.signIn("ada@example.com", "correct horse")
            advanceUntilIdle()

            accounts.deleteAccount()
            advanceUntilIdle()

            assertFalse(accounts.signedIn)
            assertEquals(1, server.deleted)
            assertNull(secrets.read(AccountState.TOKEN_NAME))
        }

    @Test
    fun myGamesAreListedAndRefreshedAfterChanges() =
        runTest {
            val accounts = accounts()
            accounts.address = "https://arcade.example.org"
            accounts.signIn("ada@example.com", "correct horse")
            advanceUntilIdle()

            val game = accounts.publish("{}")
            advanceUntilIdle()
            assertEquals(listOf(game), accounts.games)

            accounts.unpublish(game.slug)
            advanceUntilIdle()
            assertEquals(emptyList(), accounts.games)
        }
}

/** A SpawnKit server in memory. */
internal class FakeAccountService : AccountService {
    var meFailure: AccountException? = null
    var signInFailure: AccountException? = null
    var publishFailure: AccountException? = null
    val signedOut = mutableListOf<String>()
    val republished = mutableListOf<String>()
    val published = mutableListOf<ArcadeGame>()
    var deleted = 0
    private val profile = AccountProfile(1, "Ada", "ada@example.com", "free")
    var aiAvailable = true

    override suspend fun status() =
        ServerStatus(
            emailSignIn = true,
            aiAvailable = aiAvailable,
            ai = if (aiAvailable) "SpawnKit AI is available." else "SpawnKit AI is not set up on this server.",
        )

    override suspend fun register(
        email: String,
        password: String,
        displayName: String,
    ) = "Check your email for a 6-digit code to finish."

    override suspend fun verify(
        email: String,
        code: String,
    ) = AccountSession(TOKEN, profile)

    override suspend fun signIn(
        email: String,
        password: String,
    ): AccountSession {
        signInFailure?.let { throw it }
        return AccountSession(TOKEN, profile)
    }

    override suspend fun signOut(token: String) {
        signedOut += token
    }

    override suspend fun requestReset(email: String) = "If that email has a SpawnKit account, we sent it a 6-digit code."

    override suspend fun reset(
        email: String,
        code: String,
        newPassword: String,
    ) = AccountSession(TOKEN, profile)

    override suspend fun me(token: String): AccountProfile {
        meFailure?.let { throw it }
        return profile
    }

    override suspend fun deleteAccount(token: String) {
        deleted++
    }

    override suspend fun myGames(token: String) = published.toList()

    override suspend fun publish(
        token: String?,
        packageJson: String,
    ): ArcadeGame {
        publishFailure?.let { throw it }
        val slug = "00000000-0000-0000-0000-00000000000${published.size + 1}"
        return ArcadeGame(slug, "Game ${published.size + 1}", "https://arcade.example.org/play/$slug/").also { published += it }
    }

    override suspend fun republish(
        token: String,
        slug: String,
        packageJson: String,
    ): ArcadeGame {
        republished += slug
        val index = published.indexOfFirst { it.slug == slug }
        return published[index].copy(title = "Updated").also { published[index] = it }
    }

    override suspend fun unpublish(
        token: String,
        slug: String,
    ) {
        published.removeAll { it.slug == slug }
    }

    companion object {
        const val TOKEN = "secret-session-token"
    }
}
