package dev.spawnkit

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Path

/** Port used when `PORT` is not set in the environment. */
const val DEFAULT_PORT: Int = 8080

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val host = System.getenv("HOST") ?: "0.0.0.0"
    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

/** Installs health checks and the Arcade backed by the configured local data directory. */
fun Application.module() {
    routing {
        get("/health") {
            call.respondText("ok")
        }
    }
    val directory =
        System.getenv("SPAWNKIT_ARCADE_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".spawnkit", "arcade")
    arcade(ArcadeStore(directory), WebPlayerBundle { ArcadeStore::class.java.getResourceAsStream("/spawnkit/web-player.zip") })
}
