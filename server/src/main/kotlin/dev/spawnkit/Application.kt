package dev.spawnkit

import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.authRoutes
import dev.spawnkit.data.Db
import dev.spawnkit.hostedai.CharacterForgeService
import dev.spawnkit.hostedai.HostedAi
import dev.spawnkit.hostedai.KoogGateway
import dev.spawnkit.hostedai.OpenAiImageGateway
import dev.spawnkit.hostedai.SpendLedger
import dev.spawnkit.hostedai.aiRoutes
import dev.spawnkit.hostedai.characterRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Port used when `PORT` is not set in the environment. */
const val DEFAULT_PORT: Int = 8080

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = config.host) { spawnkit(config) }.start(wait = true)
}

/** The server with the configuration from the environment. */
fun Application.module() {
    spawnkit(ServerConfig.fromEnvironment())
}

/**
 * Installs every route: health, server status, accounts, the Arcade with owned games, and hosted AI when a model key is
 * configured (ADR-0010, ADR-0011).
 */
internal fun Application.spawnkit(config: ServerConfig) {
    log.info("Starting SpawnKit: $config")
    if (config.local) log.info("Local mode: without SMTP, account codes are printed to this console. Never use it in production.")
    val db = Db.open(config.databaseFile)
    val accounts = Accounts(db, config.mail)
    // Text and images share one ledger: the same monthly cap, per-account limits and hourly call count (ADR-0013).
    val ledger = SpendLedger(db, config.aiLimits)
    val hosted =
        config.anthropicKey()?.let { key ->
            HostedAi(KoogGateway(key, config.maxOutputTokens), ledger, maxOutputTokens = config.maxOutputTokens)
        }
    val forge = config.openAiKey()?.let { key -> CharacterForgeService(OpenAiImageGateway(key, config.imageModel), ledger) }
    val aiStatus = if (hosted != null) "SpawnKit AI is available." else "SpawnKit AI is not set up on this server."
    routing {
        get("/health") {
            call.respondText("ok")
        }
        get("/api/server") {
            val status =
                buildJsonObject {
                    put("emailSignIn", JsonPrimitive(accounts.emailAvailable))
                    put("aiAvailable", JsonPrimitive(hosted != null))
                    put("ai", JsonPrimitive(aiStatus))
                    put("characterAvailable", JsonPrimitive(forge != null))
                }
            call.respondText(status.toString(), ContentType.Application.Json)
        }
    }
    authRoutes(accounts)
    arcade(
        ArcadeStore(config.arcadeDirectory),
        WebPlayerBundle { ArcadeStore::class.java.getResourceAsStream("/spawnkit/web-player.zip") },
        owners = ArcadeOwners(accounts, OwnedGames(db), AdStats(db)),
    )
    aiRoutes(accounts, hosted)
    characterRoutes(accounts, forge)
}
