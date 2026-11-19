package dev.spawnkit

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Port used when `PORT` is not set in the environment. */
const val DEFAULT_PORT: Int = 8080

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/** Installs SpawnKit server routes. Publishing and arcade routes arrive in milestone M5. */
fun Application.module() {
    routing {
        get("/health") {
            call.respondText("ok")
        }
    }
}
