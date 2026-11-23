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
import dev.spawnkit.player.AdEvent
import dev.spawnkit.player.AdReporter
import dev.spawnkit.player.GamePlayer
import dev.spawnkit.player.PlayerMessage
import dev.spawnkit.player.PurchaseReporter
import kotlinx.coroutines.delay
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
    val reporter = remember { arcadeSlug()?.let(::ArcadeAdReporter) }
    LaunchedEffect(reporter) {
        val current = reporter ?: return@LaunchedEffect
        onPageHidden { current.flush() }
        while (true) {
            delay(5_000)
            current.flush()
        }
    }
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
        is Load.Ready -> GamePlayer(current.game, touchControls = hasTouchScreen(), adReporter = reporter, purchaseReporter = reporter)
    }
}

/**
 * Reports test ad views and clicks of an Arcade game (C12) to `/api/games/{slug}/ad-events`, in batches, with a random
 * play-session ID and numbered events: no personal data. Pages outside `/play/{slug}/`, such as offline web exports,
 * get no reporter, so their ads are never counted.
 */
private class ArcadeAdReporter(
    private val slug: String,
) : AdReporter,
    PurchaseReporter {
    private val session = randomSession()
    private val pending = mutableListOf<String>()
    private val bought = mutableListOf<String>()

    // One counter for ads and purchases, so the server counts each event once.
    private var next = 1

    override fun report(event: AdEvent) {
        pending += """{"n":${next++},"placement":"${event.placement.id}","kind":"${event.kind.id}"}"""
        if (pending.size >= BATCH) flush()
    }

    override fun purchased(
        product: String,
        priceCents: Long,
    ) {
        val name = product.replace("\\", "\\\\").replace("\"", "\\\"")
        bought += """{"n":${next++},"product":"$name"}"""
        flush()
    }

    fun flush() {
        if (pending.isNotEmpty()) {
            postJson("/api/games/$slug/ad-events", """{"session":"$session","events":[${pending.joinToString(",")}]}""")
            pending.clear()
        }
        if (bought.isNotEmpty()) {
            postJson("/api/games/$slug/purchase-events", """{"session":"$session","events":[${bought.joinToString(",")}]}""")
            bought.clear()
        }
    }

    private companion object {
        const val BATCH = 20
    }
}

private fun postJson(
    url: String,
    body: String,
): Unit =
    js(
        "fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body, keepalive: true })" +
            ".catch(() => {})",
    )

private fun randomSession(): String =
    js(
        "(globalThis.crypto && crypto.randomUUID) ? crypto.randomUUID() : ('s' + Math.random().toString(36).slice(2) + Date.now().toString(36))",
    )

/** The Arcade game ID from a `/play/{slug}/` address, or `null` elsewhere. */
private fun arcadeSlug(): String? = js("{ const m = location.pathname.match(/^\\/play\\/([A-Za-z0-9-]+)\\//); return m ? m[1] : null; }")

private fun onPageHidden(callback: () -> Unit): Unit = js("{ window.addEventListener('pagehide', () => callback()); }")

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
