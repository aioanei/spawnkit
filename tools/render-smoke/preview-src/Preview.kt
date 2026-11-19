package dev.spawnkit.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.spawnkit.engine.GameSession
import dev.spawnkit.model.DecodeResult
import dev.spawnkit.model.SpawnJson
import dev.spawnkit.model.VariableId
import dev.spawnkit.render.GameView
import dev.spawnkit.templates.EmbeddedTemplates

/** Test-only entry point; telemetry reads the real world without driving or mutating it. */
@Composable
fun App() {
    val session =
        remember {
            val decoded = SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))
            check(decoded is DecodeResult.Ok) { "Bundled template did not decode: $decoded" }
            GameSession(decoded.project)
        }
    LaunchedEffect(session) {
        var lastTick = -1L
        while (true) {
            withFrameNanos {
                val world = session.world
                if (world.tick != lastTick) {
                    lastTick = world.tick
                    val player = world.playerIndex
                    println(
                        "SPAWNKIT_QA {\"tick\":${world.tick},\"x\":${world.x(player)},\"y\":${world.y(player)}," +
                            "\"grounded\":${world.isGrounded(player)},\"coins\":${world.variable(VariableId("coins"))}," +
                            "\"deaths\":${world.variable(VariableId("deaths"))}}",
                    )
                }
            }
        }
    }
    GameView(session, Modifier.fillMaxSize(), showTouchControls = true)
}
