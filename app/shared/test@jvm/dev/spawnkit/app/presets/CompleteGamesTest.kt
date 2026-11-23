package dev.spawnkit.app.presets

import dev.spawnkit.editing.Project
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.player.PlaySession
import dev.spawnkit.player.playSession
import dev.spawnkit.presets.GamePresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Complete-game templates (C17) played to their endings by scripted players through the real play session: kept
 * values, spawning and test purchases behave as in the builder's Play and exported players.
 */
class CompleteGamesTest {
    private val engine = StandardEngine()

    private inner class Bot(
        val project: Project,
    ) {
        val session: PlaySession = engine.playSession(project, project.scenes.first().id)
        var ticks = 0
            private set

        val sceneName: String get() = checkNotNull(project.scene(checkNotNull(session.sceneId))).name

        fun tick() {
            ticks++
            if (session.paused) return
            session.scene.world.submitInput(session.nextInput())
            session.scene.world.step()
            session.afterTick()
        }

        /** The centre of the object named [name] in the scene being played, or `null` when it is not there. */
        fun center(name: String): Pair<Double, Double>? {
            val scene = checkNotNull(project.scene(checkNotNull(session.sceneId)))
            val id = scene.objects.firstOrNull { it.name == name }?.id ?: return null
            val entity = session.scene.entity(id) ?: return null
            val box = engine.gameplay.bounds(session.scene.world, entity) ?: return null
            return (box.left + box.right) / 2 to (box.top + box.bottom) / 2
        }

        /** Taps the object named [name] like a finger: down for one moment, up on the next. */
        fun tap(name: String) {
            val (x, y) = assertNotNull(center(name), "$name is on $sceneName")
            session.pointerDown(1, x, y)
            tick()
            session.pointerUp(1)
            tick()
        }

        /** Centres of the objects tagged [tag] in the world being played. */
        fun tagged(tag: String): List<Pair<Double, Double>> =
            engine.gameplay.tagged(session.scene.world, tag).mapNotNull { entity ->
                engine.gameplay.bounds(session.scene.world, entity)?.let { (it.left + it.right) / 2 to (it.top + it.bottom) / 2 }
            }

        /** Holds exactly the actions in [held] for one moment. */
        fun hold(held: Set<String>) {
            for (action in listOf(StandardActions.LEFT, StandardActions.RIGHT, StandardActions.JUMP)) {
                if (action in held) session.press(action, "bot") else session.release(action, "bot")
            }
            tick()
        }

        fun number(tag: String): Long {
            val world = session.scene.world
            return engine.gameplay
                .tagged(world, tag)
                .firstOrNull()
                ?.let { world.get(it, engine.gameplay.number)?.value } ?: 0
        }
    }

    private fun template(id: String) =
        GamePresets.all
            .single { it.id == id }
            .also { assertTrue(it.complete) }
            .project()

    @Test
    fun aToasterTycoonPlayerBuildsAToastEmpire() {
        val bot = Bot(template("toaster-tycoon"))
        bot.tap("Play button")
        assertEquals("Kitchen", bot.sceneName)
        while (bot.sceneName !in setOf("Toast Empire", "Shift over") && bot.ticks < 12_000) {
            val toast = bot.number("toast")
            val income = bot.number("income")
            when (bot.sceneName) {
                "Kitchen" -> {
                    when {
                        toast >= PresetRecipes.BAKERY_PRICE && income >= 40 -> bot.tap("Bakery door")
                        toast >= 100 -> bot.tap("Golden Toaster")
                        toast >= 15 && income < 5 -> bot.tap("Toaster Bot")
                        else -> bot.tap("Toaster")
                    }
                }

                "Bakery" -> {
                    if (toast >= 400) bot.tap("Mega Oven") else bot.tick()
                }

                else -> {
                    error("The bot got lost on ${bot.sceneName}")
                }
            }
        }
        assertEquals("Toast Empire", bot.sceneName, "won after ${bot.ticks} moments")
    }

    @Test
    fun anIdleToasterTycoonShiftEndsCold() {
        val bot = Bot(template("toaster-tycoon"))
        bot.tap("Play button")
        repeat((PresetRecipes.TYCOON_SECONDS * 60 + 120).toInt()) { bot.tick() }
        assertEquals("Shift over", bot.sceneName)
        bot.tap("Try again button")
        assertEquals("Title", bot.sceneName)
        bot.tap("Play button")
        assertEquals(0L, bot.number("toast"), "an ending starts the next run fresh")
        assertEquals(PresetRecipes.TYCOON_SECONDS, bot.number("time"))
    }

    @Test
    fun aStarDefenderPilotClearsThreeWaves() {
        val bot = Bot(template("star-defender"))
        bot.tap("Play button")
        assertEquals("Wave 1", bot.sceneName)
        val waves = mutableListOf(bot.sceneName)
        var fire = false
        var best = 0L
        while (bot.sceneName !in setOf("Galaxy saved", "Ship lost") && bot.ticks < 40_000) {
            if (bot.sceneName != waves.last()) waves += bot.sceneName
            best = maxOf(best, bot.number("score"))
            val ship = bot.tagged("ship").singleOrNull()
            val target = ship?.let { s -> bot.tagged("enemy").filter { it.second < s.second }.maxByOrNull { it.second } }
            if (ship == null || target == null) {
                bot.hold(emptySet())
                continue
            }
            val dx = target.first - ship.first
            val held = mutableSetOf<String>()
            if (dx < -0.1) held += StandardActions.LEFT
            if (dx > 0.1) held += StandardActions.RIGHT
            // Jump fires when pressed, so tap it every other moment while lined up.
            fire = !fire
            if (kotlin.math.abs(dx) < 0.35 && fire) held += StandardActions.JUMP
            bot.hold(held)
        }
        assertEquals("Galaxy saved", bot.sceneName, "after ${bot.ticks} moments")
        assertEquals(listOf("Wave 1", "Wave 2", "Wave 3"), waves)
        // The ending resets the score for the next run, so check the highest score seen during play.
        assertTrue(best >= 2 * PresetRecipes.WAVE_KILLS * 10, "score was kept across the waves: best $best")
    }

    @Test
    fun anIdleStarDefenderPilotLosesTheShip() {
        val bot = Bot(template("star-defender"))
        bot.tap("Play button")
        while (bot.sceneName != "Ship lost" && bot.ticks < 60_000) bot.tick()
        assertEquals("Ship lost", bot.sceneName)
    }

    @Test
    fun aSkyHopperHopsThreeLevels() {
        val bot = Bot(template("sky-hopper"))
        bot.tap("Play button")
        assertEquals("Level 1", bot.sceneName)
        val seen = mutableListOf(bot.sceneName)
        val gaps = (0 until PresetRecipes.SKY_SEGMENTS - 1).map { it * PresetRecipes.SKY_STEP + 3.0 }
        var press = false
        var coins = 0L
        var previous = emptyList<Pair<Double, Double>>()
        while (bot.sceneName !in setOf("Sky champion", "Out of lives") && bot.ticks < 30_000) {
            if (bot.sceneName != seen.last()) seen += bot.sceneName
            coins = maxOf(coins, bot.number("coins"))
            val world = bot.session.scene.world
            val hopper = engine.gameplay.tagged(world, "player").singleOrNull()
            if (hopper == null) {
                bot.hold(emptySet())
                continue
            }
            val (x, y) = bot.tagged("player").single()
            val grounded = world.get(hopper, engine.gameplay.body)?.onGround == true
            val enemies = bot.tagged("enemy")
            val gap = gaps.firstOrNull { x in it - 0.9..it - 0.2 }
            // Wait at a gap while an enemy walks where the hopper would land.
            val landingBusy = gap != null && enemies.any { it.first in gap + 0.5..gap + 4.0 }
            // Jump over an enemy only while it walks towards the hopper, so both speeds carry the hopper clear;
            // while it walks away, wait for it to turn.
            val ahead = enemies.filter { it.first - x in 0.0..3.2 && kotlin.math.abs(it.second - y) < 1.5 }
            val approaching = ahead.any { e -> previous.any { kotlin.math.abs(it.second - e.second) < 0.01 && it.first > e.first + 1e-9 } }
            val jumpEnemy = approaching && ahead.any { it.first - x in 1.1..1.5 }
            val waitEnemy = ahead.isNotEmpty() && !approaching
            previous = enemies
            val held = mutableSetOf<String>()
            if (!landingBusy && !waitEnemy) held += StandardActions.RIGHT
            press = !press
            if (grounded && press && ((gap != null && !landingBusy) || jumpEnemy)) held += StandardActions.JUMP
            bot.hold(held)
        }
        assertEquals("Sky champion", bot.sceneName, "after ${bot.ticks} moments, reached ${seen.last()}")
        assertEquals(listOf("Level 1", "Level 2", "Level 3"), seen)
        assertTrue(coins >= 6, "coins were kept across levels: $coins")
    }

    @Test
    fun aSkyHopperWhoWalksOffTheEdgeLosesEveryLife() {
        val bot = Bot(template("sky-hopper"))
        bot.tap("Play button")
        while (bot.sceneName != "Out of lives" && bot.ticks < 10_000) bot.hold(setOf(StandardActions.RIGHT))
        assertEquals("Out of lives", bot.sceneName)
    }

    @Test
    fun aSuperJumpBoughtInTheShopJumpsHigher() {
        fun peak(boost: Boolean): Double {
            val bot = Bot(template("sky-hopper"))
            if (boost) {
                bot.tap("Shop button")
                bot.tap("Super jump")
                assertEquals(
                    "Super jump",
                    bot.session
                        .completePurchase(buy = true)
                        ?.action
                        ?.product,
                )
                bot.tick()
                bot.tap("Back button")
            }
            bot.tap("Play button")
            repeat(60) { bot.hold(emptySet()) }
            val start = bot.tagged("player").single().second
            bot.hold(setOf(StandardActions.JUMP))
            var top = start
            repeat(60) {
                bot.hold(emptySet())
                top = minOf(top, bot.tagged("player").single().second)
            }
            return start - top
        }
        val normal = peak(boost = false)
        val boosted = peak(boost = true)
        assertTrue(boosted > normal + 1.0, "Super jump: $boosted vs $normal")
    }

    @Test
    fun toastBoughtInTheShopIsKeptForPlay() {
        val bot = Bot(template("toaster-tycoon"))
        bot.tap("Shop button")
        assertEquals("Shop", bot.sceneName)
        bot.tap("Toast crate")
        assertEquals(
            "1000 toast",
            bot.session
                .completePurchase(buy = true)
                ?.action
                ?.product,
        )
        // The finger lifted while the purchase question paused play; let that release reach the game.
        bot.tick()
        bot.tap("Back button")
        bot.tap("Play button")
        assertEquals("Kitchen", bot.sceneName)
        assertEquals(1000L, bot.number("toast"), "the test purchase carried into play")
        assertEquals(
            "Bakery",
            bot.run {
                tap("Bakery door")
                sceneName
            },
            "1000 toast opens the Bakery",
        )
    }
}
