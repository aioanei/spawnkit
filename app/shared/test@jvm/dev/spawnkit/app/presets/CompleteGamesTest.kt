package dev.spawnkit.app.presets

import dev.spawnkit.editing.Project
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
