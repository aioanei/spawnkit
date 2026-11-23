package dev.spawnkit.app.presets

import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.gameplay.StandardActions
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.player.PlaySession
import dev.spawnkit.player.playSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The game a live model built from one prompt (C13a, `ai/recordings/jungle-banana-run.spawnkit`), played by a scripted
 * player through the real play session: the title screen, the shop's test purchase and the first level work as built.
 */
class AiBuiltGameTest {
    private val engine = StandardEngine()

    private val project: Project =
        ProjectCodec.decode(
            listOf("../../ai/recordings", "ai/recordings")
                .map { File(it, "jungle-banana-run.spawnkit") }
                .first { it.exists() }
                .readText(),
        )

    private val session: PlaySession = engine.playSession(project, project.scenes.first().id)

    private val sceneName: String get() = checkNotNull(project.scene(checkNotNull(session.sceneId))).name

    private fun tick() {
        if (session.paused) return
        session.scene.world.submitInput(session.nextInput())
        session.scene.world.step()
        session.afterTick()
    }

    private fun tap(name: String) {
        val scene = checkNotNull(project.scene(checkNotNull(session.sceneId)))
        val id = assertNotNull(scene.objects.firstOrNull { it.name == name }, "$name is on $sceneName").id
        val box = assertNotNull(engine.gameplay.bounds(session.scene.world, assertNotNull(session.scene.entity(id))))
        session.pointerDown(1, (box.left + box.right) / 2, (box.top + box.bottom) / 2)
        tick()
        session.pointerUp(1)
        tick()
    }

    private fun number(tag: String): Long {
        val world = session.scene.world
        return engine.gameplay
            .tagged(world, tag)
            .firstOrNull()
            ?.let { world.get(it, engine.gameplay.number)?.value } ?: -1
    }

    /** Runs right and jumps on a fixed beat, ignoring snakes, until the scene changes or [seconds] pass. */
    private fun recklessRun(seconds: Int) {
        val start = sceneName
        for (t in 0 until 60 * seconds) {
            if (t % 30 == 0) session.press(StandardActions.JUMP, "bot") else session.release(StandardActions.JUMP, "bot")
            session.press(StandardActions.RIGHT, "bot")
            tick()
            if (sceneName != start) break
        }
        session.release(StandardActions.RIGHT, "bot")
        session.release(StandardActions.JUMP, "bot")
    }

    @Test
    fun theTitleLeadsToALevelWhereRunningIntoSnakesEndsOnGameOverAndRetryRestoresLives() {
        assertEquals("Title", sceneName)
        tap("Play button")
        assertEquals("Level 1", sceneName)
        assertEquals(3L, number("lives"))

        recklessRun(seconds = 40)
        assertEquals("Game Over", sceneName, "snakes take every life")
        tap("Retry button")
        assertEquals("Title", sceneName)
        tap("Play button")
        assertEquals("Level 1", sceneName)
        assertEquals(3L, number("lives"), "Retry gave the lives back for the next play")
    }

    @Test
    fun theShopSellsAnExtraLifeAsATestPurchase() {
        tap("Shop button")
        assertEquals("Shop", sceneName)
        val before = number("lives")
        tap("Extra life button")
        assertEquals("Extra life", session.completePurchase(buy = true)?.action?.product)
        tick()
        assertEquals(before + 1, number("lives"), "the bought life is added")
        tap("Back button")
        assertEquals("Title", sceneName)
    }
}
