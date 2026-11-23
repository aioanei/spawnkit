package dev.spawnkit.gameplay

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.runtime.EntityHandle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Platform physics (C14b): bodies fall, land, bounce, are blocked by solids and ride moving platforms. */
class PhysicsTest {
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
        width: Double = 1.0,
        height: Double = 1.0,
        extra: List<ComponentDoc> = emptyList(),
        rules: List<Rule> = emptyList(),
    ): String {
        val components =
            listOf(
                ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y)),
                ComponentDoc("graphics:shape", mapOf("width" to width, "height" to height)),
                ComponentDoc("gameplay:tag", mapOf("tag" to name.lowercase())),
            ) + extra
        val id =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, components = components)),
            ).createdIds.single()
        if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules)))
        return id
    }

    private fun body(vararg values: Pair<String, Any>) = ComponentDoc("gameplay:body", mapOf(*values))

    private val solid = ComponentDoc("gameplay:solid")

    private fun motion(
        vx: Double,
        vy: Double = 0.0,
    ) = ComponentDoc("gameplay:motion", mapOf("vx" to vx, "vy" to vy))

    /** A wide floor whose top edge is at y = 4.5. */
    private fun ground() = add("Ground", 0.0, 5.0, width = 40.0, extra = listOf(solid))

    private fun play(): SceneWorld = buildSceneWorld(engine.runtime, engine.playProject(editor.project), scene, engine.programs)

    private fun SceneWorld.tick(times: Int = 1) = apply { repeat(times) { world.step() } }

    private fun SceneWorld.the(tag: String): EntityHandle = engine.gameplay.tagged(world, tag).single()

    private fun SceneWorld.x(tag: String): Double = world.get(the(tag), engine.graphics.transform)!!.x

    private fun SceneWorld.y(tag: String): Double = world.get(the(tag), engine.graphics.transform)!!.y

    private fun SceneWorld.body(tag: String): Body = world.get(the(tag), engine.gameplay.body)!!

    private fun assertNear(
        expected: Double,
        actual: Double,
        message: String,
    ) = assertTrue(abs(expected - actual) < 1e-9, "$message: expected $expected, was $actual")

    @Test
    fun aBodyFallsAndLandsOnTheGround() {
        ground()
        add("Player", 0.0, 0.0, extra = listOf(body()))
        val play = play()

        play.tick(5)
        assertTrue(play.y("player") > 0.0, "gravity pulls the body down")
        assertFalse(play.body("player").onGround)

        play.tick(115)
        assertNear(4.0, play.y("player"), "the body rests on the ground's top edge")
        assertTrue(play.body("player").onGround)
        assertEquals(0.0, play.body("player").vy)
    }

    @Test
    fun aBodyFallsNoFasterThanItsFastestFall() {
        add("Player", 0.0, 0.0, extra = listOf(body("gravity" to 50.0, "maxFall" to 12.0)))
        val play = play().tick(120)

        assertEquals(12.0, play.body("player").vy)
    }

    @Test
    fun wallsBlockABodyOnBothSides() {
        add("Right wall", 3.0, 0.0, height = 10.0, extra = listOf(solid))
        add("Left wall", -3.0, 0.0, height = 10.0, extra = listOf(solid))
        add("Runner", 0.0, 0.0, extra = listOf(body("gravity" to 0.0, "vx" to 5.0)))
        add("Back", 0.0, 3.0, extra = listOf(body("gravity" to 0.0, "vx" to -5.0)))
        val play = play().tick(60)

        assertNear(2.0, play.x("runner"), "stopped at the right wall's left edge")
        assertEquals(0.0, play.body("runner").vx)
        assertNear(-2.0, play.x("back"), "stopped at the left wall's right edge")
    }

    @Test
    fun aFastBodyDoesNotPassThroughAThinWall() {
        add("Wall", 3.0, 0.0, width = 0.1, height = 10.0, extra = listOf(solid))
        add("Bullet", 0.0, 0.0, width = 0.2, height = 0.2, extra = listOf(body("gravity" to 0.0, "vx" to 600.0)))
        val play = play().tick(3)

        assertNear(2.85, play.x("bullet"), "stopped at the wall")
    }

    @Test
    fun aBouncyBodyReboundsAndSettles() {
        ground()
        add("Ball", 0.0, 0.0, extra = listOf(body("bounce" to 0.5)))
        val play = play()

        var rebounded = false
        repeat(60) {
            play.tick()
            if (play.body("ball").vy < 0.0) rebounded = true
        }
        assertTrue(rebounded, "the ball bounces up after landing")
        play.tick(600)
        assertNear(4.0, play.y("ball"), "small rebounds stop, so the ball comes to rest")
        assertTrue(play.body("ball").onGround)
    }

    @Test
    fun aBodyWalksAlongTheGroundWithMoveBlocks() {
        ground()
        add(
            "Player",
            0.0,
            4.0,
            extra = listOf(body()),
            rules = listOf(Rule(Condition.Always, actions = listOf(Action.Move(Direction.Right, 3.0)))),
        )
        val play = play().tick(60)

        assertNear(3.0, play.x("player"), "the floor does not slow walking")
        assertNear(4.0, play.y("player"), "the body stays on the floor")
        assertTrue(play.body("player").onGround)
    }

    @Test
    fun aMoveBlockCannotWalkABodyThroughAWall() {
        ground()
        add("Wall", 3.0, 3.0, height = 2.0, extra = listOf(solid))
        add(
            "Player",
            0.0,
            4.0,
            extra = listOf(body()),
            rules = listOf(Rule(Condition.Always, actions = listOf(Action.Move(Direction.Right, 6.0)))),
        )
        val play = play().tick(60)

        assertNear(2.0, play.x("player"), "pushed back out of the wall")
        assertNear(4.0, play.y("player"), "still on the floor")
    }

    @Test
    fun aBodyRidesAPlatformMovingAcross() {
        add("Platform", 0.0, 5.0, width = 4.0, extra = listOf(solid, motion(2.0)))
        add("Player", 0.0, 4.0, extra = listOf(body()))
        // The first moment finds out what the player stands on; from then on it rides.
        val play = play().tick()
        val start = play.x("player")
        play.tick(60)

        assertNear(start + 2.0, play.x("player"), "the player moved with the platform")
        assertNear(play.x("platform") - 2.0 / 60, play.x("player"), "one moment behind it")
        assertNear(4.0, play.y("player"), "and stayed on top")
    }

    @Test
    fun aBodyRidesARisingPlatform() {
        add("Lift", 0.0, 5.0, width = 4.0, extra = listOf(solid, motion(0.0, -1.0)))
        add("Player", 0.0, 4.0, extra = listOf(body()))
        val play = play().tick(60)

        assertNear(3.0, play.y("player"), "carried up by the rising lift")
        assertTrue(play.body("player").onGround)
    }

    @Test
    fun aBodyRidesASinkingPlatformWithoutFallingOff() {
        add("Lift", 0.0, 5.0, width = 4.0, extra = listOf(solid, motion(0.0, 2.0)))
        add("Player", 0.0, 4.0, extra = listOf(body()))
        val play = play().tick(60)

        assertNear(6.0, play.y("player"), "carried down with the lift")
        assertTrue(play.body("player").onGround)
    }

    @Test
    fun aRisingPlatformPushesABodyUp() {
        add("Block", 0.0, 4.6, extra = listOf(solid))
        add("Player", 0.0, 4.0, extra = listOf(body("gravity" to 0.0)))
        val play = play().tick()

        assertNear(3.6, play.y("player"), "pushed up the shorter way out")
        assertNear(0.0, play.x("player"), "not sideways")
    }

    @Test
    fun aBodyPlacedInTheMiddleOfASolidEndsUpOnTop() {
        add("Platform", 0.0, 0.0, width = 6.0, height = 0.6, extra = listOf(solid))
        add("Player", 0.0, 0.0, width = 0.9, height = 0.9, extra = listOf(body()))
        val play = play().tick(10)

        assertNear(-0.75, play.y("player"), "standing on the platform")
        assertTrue(play.body("player").onGround)
    }

    @Test
    fun aBodyOnASolidIsNotBlockedByItself() {
        ground()
        add("Crate", 0.0, 0.0, extra = listOf(body(), solid))
        add("Player", 0.0, -3.0, extra = listOf(body()))
        val play = play().tick(120)

        assertNear(4.0, play.y("crate"), "the crate falls to the ground")
        assertNear(3.0, play.y("player"), "the player lands on the crate")
    }

    @Test
    fun aJumpReplaysIdenticallyFromACheckpoint() {
        ground()
        add("Player", 0.0, 0.0, extra = listOf(body("bounce" to 0.3, "vx" to 1.5)))
        add("Lift", 4.0, 4.0, width = 2.0, extra = listOf(solid, motion(-1.0, -0.5)))
        val live = play().tick(20)
        val snapshot = live.world.snapshot()
        live.tick(100)

        val replay = engine.runtime.restore(snapshot)
        repeat(100) { replay.step() }
        assertEquals(live.world.stateHash(), replay.stateHash())
    }
}
