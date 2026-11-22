package dev.spawnkit.presets

import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.editing.capabilityProblems
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Bounds
import dev.spawnkit.gameplay.RuleBlockCatalog
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.Pointer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every preset game is a playable project (C10): it loads, the real engine finds no problems, its title screen leads
 * into the game, and a scripted player reaches the game's ending. The scripts read object positions the way a person
 * watches the screen and press the same inputs a person would.
 */
class PresetGamesTest {
    private val engine = StandardEngine()

    private fun preset(id: String): Project = GamePresets.all.single { it.id == id }.project()

    /** A headless play session that follows "Go to scene" exactly like the builder's Play and exported players. */
    private inner class Run(
        val project: Project,
        startScene: String = project.scenes[1].id,
    ) {
        var sceneId = startScene
            private set
        var scene: SceneWorld = build(startScene)
            private set

        /** Scene changes so far, including restarts of the same scene. */
        var changes = 0
            private set
        private var nextPointer = 1

        val sceneName: String get() = checkNotNull(project.scene(sceneId)).name

        private fun build(id: String) = buildSceneWorld(engine.runtime, project, id, engine.programs)

        fun tick(
            held: Set<String> = emptySet(),
            pointers: List<Pointer> = emptyList(),
        ) {
            scene.world.submitInput(InputFrame(down = held.map { engine.actions.getValue(it) }.toSet(), pointers = pointers))
            scene.world.step()
            engine.takeSceneRequest(scene.world)?.let {
                sceneId = it
                scene = build(it)
                changes++
            }
        }

        /** A quick tap at ([x], [y]): pressed for one tick, released on the next. */
        fun tap(
            x: Double,
            y: Double,
        ) {
            tick(pointers = listOf(Pointer(nextPointer++, x, y, pressed = true)))
            tick()
        }

        fun tagged(tag: String): List<Bounds> =
            engine.gameplay.tagged(scene.world, tag).mapNotNull { engine.gameplay.bounds(scene.world, it) }

        /** Holds the arrow keys that move the player towards ([x], [y]) for one tick; true once it is there. */
        fun steer(
            x: Double,
            y: Double,
        ): Boolean {
            val player = tagged("player").single()
            val dx = x - player.centerX
            val dy = y - player.centerY
            val held = mutableSetOf<String>()
            if (dx > CLOSE) held += StandardActions.RIGHT
            if (dx < -CLOSE) held += StandardActions.LEFT
            if (dy > CLOSE) held += StandardActions.DOWN
            if (dy < -CLOSE) held += StandardActions.UP
            if (held.isEmpty()) return true
            tick(held)
            return false
        }
    }

    @Test
    fun catalogMatchesTheEmbeddedFiles() {
        assertEquals(listOf("coin-dash", "tap-targets", "maze-run"), GamePresets.all.map { it.id })
        assertEquals(EmbeddedPresets.all.keys, GamePresets.all.map { it.id }.toSet())
        GamePresets.all.forEach { assertEquals(it.name, it.project().name, "name of ${it.id}") }
        GamePresets.all.forEach { assertTrue(it.pitch.isNotBlank(), "pitch of ${it.id}") }
        GamePresets.all.forEach { assertTrue(it.teaches.isNotEmpty(), "blocks taught by ${it.id}") }
    }

    @Test
    fun listedBlocksAreUsed() {
        GamePresets.all.forEach { preset ->
            val used =
                preset
                    .project()
                    .scenes
                    .flatMap { it.objects }
                    .flatMap { obj -> RuleBlocks.decompile(obj.rules).filterIsInstance<RuleItem.Blocks>() }
                    .flatMap { item ->
                        (listOf(item.rule.condition) + item.rule.alsoIf).map { RuleBlockCatalog.spec(it).authoring.name } +
                            item.rule.actions.map { RuleBlockCatalog.spec(it).authoring.name }
                    }.toSet()
            assertEquals(emptyList(), preset.teaches.filter { it !in used }, "${preset.id} lists blocks it does not use; it uses $used")
        }
    }

    @Test
    fun everyPresetExplainsHowItWorks() {
        GamePresets.all.forEach { preset ->
            val project = preset.project()
            val run = Run(project, project.scenes.first().id)
            val how = run.tagged("how-button").single()

            run.tap(how.centerX, how.centerY)

            assertEquals("How it works", run.sceneName, "${preset.id}: the title's How it works button")
            assertTrue(
                project.scene(run.sceneId)!!.objects.count { it.component("graphics:text") != null } >= 4,
                "${preset.id} explains its rules",
            )
            val back = run.tagged("back-button").single()
            run.tap(back.centerX, back.centerY)
            assertEquals("Title", run.sceneName, "${preset.id}: Back returns to the title")
        }
    }

    @Test
    fun everyPresetHasNoProblems() {
        GamePresets.all.forEach { preset ->
            val project = preset.project()
            assertEquals(emptyList(), project.capabilityProblems(engine.runtime), preset.id)
            project.scenes.forEach { scene ->
                val world = buildSceneWorld(engine.runtime, project, scene.id, engine.programs)
                assertEquals(emptyMap(), world.diagnostics, "${preset.id} / ${scene.name}")
                val targets =
                    scene.objects
                        .flatMap { obj ->
                            RuleBlocks.decompile(obj.rules).filterIsInstance<RuleItem.Blocks>().flatMap { it.rule.actions }
                        }.filterIsInstance<Action.GoToScene>()
                targets.forEach { assertTrue(project.scene(it.sceneId) != null, "${preset.id} / ${scene.name} goes to a missing scene") }
            }
        }
    }

    @Test
    fun tappingPlayOnEachTitleStartsTheGame() {
        GamePresets.all.forEach { preset ->
            val project = preset.project()
            val run = Run(project, project.scenes.first().id)
            assertEquals("Title", run.sceneName, "${preset.id} starts on its title screen")
            val button = run.tagged("play-button").single()

            run.tap(button.centerX, button.centerY)

            assertEquals(project.scenes[1].id, run.sceneId, "${preset.id}: Play opens the game")
        }
    }

    @Test
    fun coinDashBotCollectsEveryCoinAndWins() {
        val run = Run(preset("coin-dash"))
        assertTrue(run.tagged("coin").size >= 5, "Coin Dash has coins to collect")

        repeat(SECONDS_LIMIT * TICKS) {
            if (run.sceneName != "Level") return@repeat
            val coins = run.tagged("coin")
            val player = run.tagged("player").single()
            val next = coins.minByOrNull { abs(it.centerX - player.centerX) + abs(it.centerY - player.centerY) }
            if (next == null) run.tick() else run.steer(next.centerX, next.centerY)
        }

        assertEquals("You win", run.sceneName)
    }

    @Test
    fun tapTargetsBotTapsEveryTargetAndWins() {
        val run = Run(preset("tap-targets"))
        assertTrue(run.tagged("target").size >= 5, "Tap Targets has targets")

        repeat(SECONDS_LIMIT * TICKS) {
            if (run.sceneName != "Play") return@repeat
            val target = run.tagged("target").firstOrNull()
            if (target == null) run.tick() else run.tap(target.centerX, target.centerY)
        }

        assertEquals("You win", run.sceneName)
    }

    @Test
    fun tapTargetsTimeRunsOutWithoutTaps() {
        val run = Run(preset("tap-targets"))
        var ticks = 0

        while (run.sceneName == "Play" && ticks < SECONDS_LIMIT * TICKS) {
            run.tick()
            ticks++
        }

        assertEquals("Time's up", run.sceneName)
        assertTrue(ticks in (COUNTDOWN - 1) * TICKS..(COUNTDOWN + 1) * TICKS, "time ran out after $ticks ticks")
    }

    @Test
    fun mazeRunBotEscapes() {
        val run = Run(preset("maze-run"))
        val route = listOf(-3.0 to 2.5, -1.0 to 2.5, -1.0 to -2.5, 1.0 to -2.5, 1.0 to 2.5, 3.0 to 2.5, 3.0 to -2.5)
        var next = 0

        repeat(SECONDS_LIMIT * TICKS) {
            if (run.sceneName != "Maze" || next == route.size) return@repeat
            if (run.steer(route[next].first, route[next].second)) next++
        }

        assertEquals("You escaped", run.sceneName)
        assertEquals(1, run.changes, "the only scene change is the escape: the route never touches a wall")
    }

    @Test
    fun mazeWallsRestartTheMaze() {
        val run = Run(preset("maze-run"))
        val start = run.tagged("player").single()

        repeat(TICKS) { if (run.changes == 0) run.tick(setOf(StandardActions.UP)) }

        assertEquals(1, run.changes, "touching the top wall restarts the maze")
        assertEquals("Maze", run.sceneName)
        assertEquals(start, run.tagged("player").single(), "the player is back at the start")
    }

    private val Bounds.centerX get() = (left + right) / 2
    private val Bounds.centerY get() = (top + bottom) / 2

    private companion object {
        const val TICKS = 60
        const val SECONDS_LIMIT = 60
        const val COUNTDOWN = 20
        const val CLOSE = 0.1
    }
}
