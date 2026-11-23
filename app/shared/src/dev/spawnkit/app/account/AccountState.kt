package dev.spawnkit.app.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.ai.AccountClient
import dev.spawnkit.ai.AccountException
import dev.spawnkit.ai.AccountProfile
import dev.spawnkit.ai.AccountService
import dev.spawnkit.ai.AccountSession
import dev.spawnkit.ai.AdStatsReport
import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.ArcadeGame
import dev.spawnkit.ai.CharacterResult
import dev.spawnkit.ai.HostedModel
import dev.spawnkit.ai.ServerStatus
import dev.spawnkit.app.assistant.HostedChoice
import dev.spawnkit.app.assistant.PhotoForge
import dev.spawnkit.app.assistant.SecretStore
import dev.spawnkit.editing.ProjectImages
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One HTTP client for every SpawnKit server call; the platform's engine is found on the classpath. */
internal val sharedHttpClient: HttpClient by lazy { HttpClient() }

/**
 * The creator's SpawnKit account in the builder (C11b, ADR-0010): which server, who is signed in, and the session.
 * The session token lives only in [secrets]; it is never shown, logged or put in projects. A token the server no longer
 * accepts signs the builder out with a plain notice. Network calls run in [scope]; their failures become [notice]s.
 */
class AccountState(
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    private val service: (address: String) -> AccountService = { AccountClient(it, sharedHttpClient) },
    private val hostedModel: (
        address: String,
        token: String,
    ) -> AgentModel = { address, token -> HostedModel(address, token, sharedHttpClient) },
) {
    /** The server address being typed or used. */
    var address by mutableStateOf(secrets.read(SERVER_NAME).orEmpty())

    /** What the server offers, once checked. */
    var status by mutableStateOf<ServerStatus?>(null)
        private set

    /** The signed-in account, or `null`. */
    var account by mutableStateOf<AccountProfile?>(null)
        private set

    /** A request is running. */
    var busy by mutableStateOf(false)
        private set

    /** The last outcome worth telling the creator, in plain words. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** The email waiting for its sign-up code, or `null`. */
    var awaitingCode by mutableStateOf<String?>(null)
        private set

    /** The email waiting for its reset code, or `null`. */
    var awaitingReset by mutableStateOf<String?>(null)
        private set

    /** The signed-in account's Arcade games, newest change first. */
    val games = mutableStateListOf<ArcadeGame>()

    val signedIn: Boolean get() = account != null

    /** The server the account belongs to, once signed in. */
    val server: String? get() = if (signedIn) secrets.read(SERVER_NAME) else null

    /** The session token, for the hosted model and publishing only. */
    internal fun token(): String? = if (signedIn) secrets.read(TOKEN_NAME) else null

    /** SpawnKit AI for the signed-in account, when its server offers it (ADR-0011); `null` otherwise. */
    fun hostedChoice(): HostedChoice? {
        val signedInAs = account ?: return null
        val at = server ?: return null
        if (status?.aiAvailable != true) return null
        val token = token() ?: return null
        return HostedChoice("$at#${signedInAs.id}", hostedModel(at, token))
    }

    /** Restores a saved session: the server must still accept its token. */
    fun start() {
        val saved = secrets.read(SERVER_NAME) ?: return
        val token = secrets.read(TOKEN_NAME) ?: return
        run {
            val api = service(saved)
            status = api.status()
            account = api.me(token)
            games.replaceWith(api.myGames(token))
        }
    }

    /** Checks the server at [address] and what it offers. */
    fun checkServer() {
        run { status = api().status() }
    }

    fun register(
        email: String,
        password: String,
        displayName: String,
    ) {
        run {
            notice = api().register(email.trim(), password, displayName.trim())
            awaitingCode = email.trim()
        }
    }

    /** Finishes sign-up with the emailed [code]. */
    fun verify(code: String) {
        val email = awaitingCode ?: return
        run {
            signedIn(api().verify(email, code.trim()))
            awaitingCode = null
        }
    }

    fun signIn(
        email: String,
        password: String,
    ) {
        run { signedIn(api().signIn(email.trim(), password)) }
    }

    fun requestReset(email: String) {
        run {
            notice = api().requestReset(email.trim())
            awaitingReset = email.trim()
        }
    }

    /** Sets a new password with the emailed [code] and signs in. */
    fun reset(
        code: String,
        newPassword: String,
    ) {
        val email = awaitingReset ?: return
        run {
            signedIn(api().reset(email, code.trim(), newPassword))
            awaitingReset = null
        }
    }

    /** Signs out here and, when it can be reached, on the server. */
    fun signOut() {
        val token = token() ?: return clear(null)
        val api = service(secrets.read(SERVER_NAME) ?: return clear(null))
        clear("Signed out.")
        scope.launch {
            try {
                api.signOut(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Signed out here already; the server's session expires on its own.
            }
        }
    }

    /** Deletes the account. Its published games stay playable, unowned. */
    fun deleteAccount() {
        val token = token() ?: return
        run {
            signedInApi().deleteAccount(token)
            clear("Your account was deleted. Games you published stay playable.")
        }
    }

    /** Reloads My games. */
    fun refreshGames() {
        val token = token() ?: return
        run { games.replaceWith(signedInApi().myGames(token)) }
    }

    /** Publishes [packageJson] as the signed-in account. Throws [AccountException] with a plain message. */
    suspend fun publish(packageJson: String): ArcadeGame = owned { api, token -> api.publish(token, packageJson) }

    /** Replaces the owned game [slug] with [packageJson]. */
    suspend fun republish(
        slug: String,
        packageJson: String,
    ): ArcadeGame = owned { api, token -> api.republish(token, slug, packageJson) }

    /** Unpublishes the owned game [slug]. */
    suspend fun unpublish(slug: String) {
        owned { api, token -> api.unpublish(token, slug) }
    }

    /**
     * Makes characters from [png] in [styles] through the server's Character Forge (ADR-0013). The photo is stripped
     * of metadata here, before it leaves the device. Throws [AccountException] with a plain message.
     */
    suspend fun forge(
        png: ByteArray,
        styles: List<String>,
        consent: Boolean,
    ): CharacterResult {
        val token = token() ?: throw AccountException(SIGN_IN_FIRST, 401)
        val photo = ProjectImages.encode(ProjectImages.stripMetadata(png))
        return signedInApi().character(token, photo, styles, consent)
    }

    /** Character Forge for the assistant (C16b), when signed in to a server that offers it; otherwise `null`. */
    fun photoForge(): PhotoForge? {
        if (!signedIn || status?.characterAvailable != true) return null
        return PhotoForge(::forge)
    }

    /** Test ad statistics of the owned game [slug] from Arcade plays (C12). Throws [AccountException] with a plain message. */
    suspend fun adStats(slug: String): AdStatsReport {
        val token = token() ?: throw AccountException(SIGN_IN_FIRST, 401)
        return signedInApi().adStats(token, slug)
    }

    override fun toString(): String = "AccountState(server=${secrets.read(SERVER_NAME)}, account=$account)"

    private suspend fun <T> owned(work: suspend (AccountService, String) -> T): T {
        val token = token() ?: throw AccountException(SIGN_IN_FIRST, 401)
        val api = signedInApi()
        return try {
            work(api, token).also { games.replaceWith(api.myGames(token)) }
        } catch (problem: AccountException) {
            if (problem.status == 401) {
                clear(SESSION_ENDED)
                throw AccountException(SESSION_ENDED, 401)
            }
            throw problem
        }
    }

    private fun signedIn(session: AccountSession) {
        val server = checkedAddress(address)
        secrets.write(SERVER_NAME, server)
        secrets.write(TOKEN_NAME, session.token)
        address = server
        account = session.account
        notice = null
        games.clear()
        scope.launch {
            try {
                val api = service(server)
                status = api.status()
                games.replaceWith(api.myGames(session.token))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Signed in already; the list and status load again when the creator opens them.
            }
        }
    }

    private fun clear(message: String?) {
        secrets.delete(TOKEN_NAME)
        account = null
        games.clear()
        awaitingCode = null
        awaitingReset = null
        notice = message
    }

    private fun api(): AccountService = service(checkedAddress(address))

    private fun signedInApi(): AccountService = service(secrets.read(SERVER_NAME) ?: throw AccountException(SIGN_IN_FIRST, 401))

    /** Runs [work] in the background, one request at a time, turning failures into [notice]. */
    private fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: AccountException) {
                // With a saved token, a 401 means the session ended; without one it is a wrong password or code.
                if (problem.status == 401 && secrets.read(TOKEN_NAME) != null) clear(SESSION_ENDED) else notice = problem.message
            } catch (problem: IllegalArgumentException) {
                notice = problem.message
            } finally {
                busy = false
            }
        }
    }

    private fun MutableList<ArcadeGame>.replaceWith(items: List<ArcadeGame>) {
        clear()
        addAll(items)
    }

    companion object {
        /** Secret name of the session token. */
        const val TOKEN_NAME = "spawnkit-session"

        /** Secret name of the server address the session belongs to. */
        const val SERVER_NAME = "spawnkit-server"

        private const val SIGN_IN_FIRST = "Sign in to your SpawnKit account first."
        private const val SESSION_ENDED = "Your session ended. Sign in again."
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /**
         * [text] as a server address: HTTPS, or plain HTTP only to this device or the Android emulator's host. Throws
         * [IllegalArgumentException] with a plain explanation otherwise.
         */
        fun checkedAddress(text: String): String {
            val trimmed = text.trim().trimEnd('/')
            val url = runCatching { Url(trimmed) }.getOrNull()
            val secure = url?.protocol?.name == "https" || (url?.protocol?.name == "http" && url.host.lowercase() in LOOPBACK)
            val plain =
                url != null && url.user == null && url.encodedQuery.isEmpty() && url.fragment.isEmpty() &&
                    url.encodedPath.trim('/').isEmpty()
            require(trimmed.contains("://") && url != null && url.host.isNotEmpty() && secure && plain) {
                "Enter the server's HTTPS address, for example https://arcade.example.org, or http://localhost for a local server."
            }
            return trimmed
        }
    }
}
