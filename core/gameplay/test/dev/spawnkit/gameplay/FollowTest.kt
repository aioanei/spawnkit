package dev.spawnkit.gameplay

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.graphics.Transform2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Camera follow (C14b): a camera that stays with a tagged object, for levels wider than the screen. */
class FollowTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private fun add(
        name: String,
        x: Double,
        y: Double,
        extra: List<ComponentDoc> = emptyList(),
        rules: List<Rule> = emptyList(),
        parent: String? = null,
        scale: Double = 1.0,
    ): String {
        val components =
            listOf(
                ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y, "scaleX" to scale, "scaleY" to scale)),
                ComponentDoc("gameplay:tag", mapOf("tag" to name.lowercase())),
            ) + extra
        val id =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, parent = parent, components = components)),
            ).createdIds.single()
        if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules)))
        return id
    }

    private val box = ComponentDoc("graphics:shape", mapOf("width" to 1.0, "height" to 1.0))

    private fun camera(vararg follow: Pair<String, Any>) =
        listOf(ComponentDoc("graphics:camera"), ComponentDoc("gameplay:follow", mapOf(*follow)))

    private fun runner(speed: Double = 3.0) =
        add("Player", 0.0, 0.0, listOf(box), listOf(Rule(Condition.Always, actions = listOf(Action.Move(Direction.Right, speed)))))

    private fun play(): SceneWorld = buildSceneWorld(engine.runtime, engine.playProject(editor.project), scene, engine.programs)

    private fun SceneWorld.tick(times: Int) = apply { repeat(times) { world.step() } }

    private fun SceneWorld.at(tag: String): Transform2D =
        world.get(engine.gameplay.tagged(world, tag).single(), engine.graphics.transform)!!

    private fun assertNear(
        expected: Double,
        actual: Double,
        message: String,
    ) = assertTrue(abs(expected - actual) < 1e-9, "$message: expected $expected, was $actual")

    @Test
    fun aCameraFollowsAcrossOnly() {
        runner()
        add("Camera", 0.0, 1.0, camera("tag" to "player", "down" to false))
        val play = play().tick(60)

        assertNear(3.0, play.at("camera").x, "the camera is above the player")
        assertEquals(1.0, play.at("camera").y, "and keeps its height")
    }

    @Test
    fun aCameraFollowsAFallingBodyOnBothAxes() {
        add("Player", 2.0, 0.0, listOf(box, ComponentDoc("gameplay:body")))
        add("Camera", 0.0, 0.0, camera())
        val play = play().tick(30)

        assertEquals(play.at("player").x, play.at("camera").x)
        assertEquals(play.at("player").y, play.at("camera").y, "the camera followed the fall in the same moment")
        assertTrue(play.at("camera").y > 1.0)
    }

    @Test
    fun withoutATaggedObjectTheCameraStays() {
        add("Camera", 4.0, 2.0, camera("tag" to "nobody"))
        val play = play().tick(10)

        assertEquals(4.0, play.at("camera").x)
        assertEquals(2.0, play.at("camera").y)
    }

    @Test
    fun aCameraInsideAGroupFollowsInWorldUnits() {
        runner()
        val group = add("Rig", 10.0, 0.0, scale = 2.0)
        add("Camera", 0.0, 0.0, camera("tag" to "player"), parent = group)
        val play = play().tick(60)

        assertNear((3.0 - 10.0) / 2.0, play.at("camera").x, "local position that puts the camera on the player")
    }

    @Test
    fun leavingThePlayAreaUsesTheFollowingCamera() {
        runner(speed = 30.0)
        add("Camera", 0.0, 0.0, camera("tag" to "player"))
        // An 80-unit area: both start in view of the camera at 0; after the run it spans -10 to 70.
        val leave = listOf(Rule(Condition.LeftArea(80.0, 24.0), actions = listOf(Action.RemoveSelf)))
        add("Behind", -15.0, 0.0, listOf(box), leave)
        add("Ahead", 30.0, 0.0, listOf(box), leave)
        val play = play().tick(60)

        assertEquals(0, engine.gameplay.countTagged(play.world, "behind"), "left behind by the camera")
        assertEquals(1, engine.gameplay.countTagged(play.world, "ahead"), "still in view")
    }
}
