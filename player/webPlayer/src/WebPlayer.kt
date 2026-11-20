@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.spawnkit.player.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.player.GamePlayer
import dev.spawnkit.player.PlayerMessage
import kotlin.js.ExperimentalWasmJsInterop

/** The package the player loads, next to its `index.html` (written by web export and served by the Arcade). */
private const val GAME_FILE = "game.spawnkit.json"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { WebPlayer() }
}

private sealed interface Load {
    data object Loading : Load

    data class Ready(
        val game: GamePackage,
    ) : Load

    data class Failed(
        val title: String,
        val detail: String,
    ) : Load
}

@Composable
private fun WebPlayer() {
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    LaunchedEffect(Unit) {
        fetchText(
            GAME_FILE,
            onText = { text ->
                load =
                    try {
                        Load.Ready(GamePackageCodec.decode(text)).also { setTitle(it.game.title) }
                    } catch (problem: IllegalArgumentException) {
                        Load.Failed("This game cannot be played", problem.message ?: "The game file is damaged.")
                    }
            },
            onError = { reason ->
                load = Load.Failed("No game found", "The player could not load $GAME_FILE next to this page ($reason).")
            },
        )
    }
    when (val current = load) {
        Load.Loading -> PlayerMessage("Loading game…")
        is Load.Failed -> PlayerMessage(current.title, listOf(current.detail))
        is Load.Ready -> GamePlayer(current.game, touchControls = hasTouchScreen())
    }
}

private fun fetchText(
    url: String,
    onText: (String) -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        "fetch(url, { cache: 'no-store' })" +
            ".then(r => r.ok ? r.text() : Promise.reject('HTTP ' + r.status))" +
            ".then(t => onText(t), e => onError(String(e)))",
    )

private fun hasTouchScreen(): Boolean = js("('ontouchstart' in window) || navigator.maxTouchPoints > 0")

private fun setTitle(title: String): Unit = js("{ document.title = title; }")
