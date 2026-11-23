package dev.spawnkit.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.capabilityProblems
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.graphics.CameraView

/** World units are drawn this many density-independent pixels wide, the same scale the builder uses. */
const val PLAYER_UNIT_DP: Float = 48f

/**
 * Plays [pkg] as a standalone game with the same engine, rules and input path as the builder's Play mode. A game
 * that needs capabilities this player does not install is explained instead of being run with parts missing.
 */
@Composable
fun GamePlayer(
    pkg: GamePackage,
    touchControls: Boolean,
    modifier: Modifier = Modifier,
    adReporter: AdReporter? = null,
) {
    val engine = remember { StandardEngine() }
    val problems = remember(pkg) { pkg.project.capabilityProblems(engine.runtime) }
    if (problems.isNotEmpty()) {
        PlayerMessage("${pkg.title} can't run in this player", problems, modifier)
        return
    }
    val session =
        remember(pkg) { engine.playSession(pkg.project, pkg.startScene) }
    val focus = remember { FocusRequester() }
    val images = rememberProjectImages(pkg.project.images)
    val pixelsPerUnit = with(LocalDensity.current) { PLAYER_UNIT_DP.dp.toPx().toDouble() }
    Box(
        modifier
            .fillMaxSize()
            .background(PlayerBackground)
            .testTag("game-player")
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { session.handlePlayKey(it) },
    ) {
        PlaySurface(session, engine.graphics, CameraView(zoom = 1.0), pixelsPerUnit, touchControls, focus, images)
        AdLayer(session, pkg.project.ads, pkg.startScene, adReporter)
        RuleProblemBanner(session, engine, pkg, Modifier.align(Alignment.TopCenter))
    }
    LaunchedEffect(session) { focus.requestFocus() }
}

/** Explains rules that stopped or could not run, so a released game never looks frozen without a reason. */
@Composable
private fun RuleProblemBanner(
    session: PlaySession,
    engine: StandardEngine,
    pkg: GamePackage,
    modifier: Modifier,
) {
    // Re-checked after every presented frame: faults happen while playing.
    session.frame
    val problem = ruleProblem(session, engine, pkg) ?: return
    Text(
        problem,
        color = Color.White,
        fontSize = 15.sp,
        modifier =
            modifier
                .padding(12.dp)
                .background(Color(0xCC8E2B2B), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("player-problem"),
    )
}

/** The builder's play-status wording without technical details: runtime faults first, then rules that could not run. */
internal fun ruleProblem(
    session: PlaySession,
    engine: StandardEngine,
    pkg: GamePackage,
): String? {
    session.message?.let { return it }
    val world = session.scene.world
    val objects =
        pkg.project
            .scene(session.sceneId ?: pkg.startScene)
            ?.objects
            .orEmpty()
            .associateBy { it.id }
    for (entity in world.entities(engine.programs.behavior)) {
        world.get(entity, engine.programs.behavior)?.fault ?: continue
        val name = session.scene.objectId(entity)?.let { objects[it]?.name } ?: "An object"
        return "$name's rules stopped working, so it no longer reacts"
    }
    val broken =
        session.scene.diagnostics.keys
            .mapNotNull { objects[it]?.name }
    if (broken.isEmpty()) return null
    return "${broken.joinToString()} ${if (broken.size == 1) "has rules that can't run" else "have rules that can't run"}, so " +
        "${if (broken.size == 1) "it plays" else "they play"} without them"
}

/** A full-screen explanation shown instead of a game, for example when the game file is missing or unsupported. */
@Composable
fun PlayerMessage(
    title: String,
    details: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(PlayerBackground).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, modifier = Modifier.testTag("player-message"))
            details.forEach { Text(it, color = Color(0xFFCFD8DC), fontSize = 16.sp) }
        }
    }
}

private val PlayerBackground = Color(0xFF1B1D23)
