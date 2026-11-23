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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The physics blocks (C14b): On the ground, Jump and Set speed. */
class PhysicsBlocksTest {
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
        width: Double,
        extra: List<ComponentDoc>,
        rules: List<Rule> = emptyList(),
    ) {
        val components =
            listOf(
                ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y)),
                ComponentDoc("graphics:shape", mapOf("width" to width, "height" to 1.0)),
                ComponentDoc("gameplay:tag", mapOf("tag" to name.lowercase())),
            ) + extra
        val id =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, components = components)),
            ).createdIds.single()
        if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules)))
    }

    private fun play(): SceneWorld = buildSceneWorld(engine.runtime, engine.playProject(editor.project), scene, engine.programs)

    private fun SceneWorld.player(): EntityHandle = engine.gameplay.tagged(world, "player").single()

    private fun SceneWorld.y(): Double = world.get(player(), engine.graphics.transform)!!.y

    private fun SceneWorld.x(): Double = world.get(player(), engine.graphics.transform)!!.x

    private fun SceneWorld.body(): Body = world.get(player(), engine.gameplay.body)!!

    private fun ground() = add("Ground", 0.0, 5.0, 40.0, listOf(ComponentDoc("gameplay:solid")))

    @Test
    fun physicsBlocksRoundTripThroughProgramsAndTheCatalog() {
        val actions =
            listOf(
                Action.Jump(15.0),
                Action.SetSpeed(Direction.Right, 8.0),
                Action.SetSpeed(Direction.Left, 0.0),
                Action.SetSpeed(Direction.Up, 3.5),
                Action.SetSpeed(Direction.Down, 2.0),
            )
        val rule = Rule(Condition.KeyPressed(StandardActions.JUMP), listOf(Condition.OnGround), actions)

        assertEquals(listOf(RuleItem.Blocks(rule)), RuleBlocks.decompile(RuleBlocks.compile(listOf(rule))))
        assertEquals(BlockResult.Created(Condition.OnGround), RuleBlockCatalog.condition(RuleBlockCatalog.picked(Condition.OnGround)))
        for (action in actions) {
            assertEquals(BlockResult.Created(action), RuleBlockCatalog.action(RuleBlockCatalog.picked(action)))
        }
        assertEquals("On the ground", RuleBlocks.describe(Condition.OnGround))
        assertEquals("Jump at 15 per second", RuleBlocks.describe(actions[0]))
        assertEquals("Set speed left to 0 per second", RuleBlocks.describe(actions[2]))
    }

    @Test
    fun jumpWorksOnlyFromTheGround() {
        ground()
        add("Player", 0.0, 0.0, 1.0, listOf(ComponentDoc("gameplay:body")), listOf(Rule(Condition.Always, actions = listOf(Action.Jump(15.0)))))
        val play = play()

        var landed = 0
        while (!play.body().onGround) {
            play.world.step()
            assertTrue(play.body().vy >= 0.0, "no jump in the air")
            landed++
            check(landed < 120) { "the player never landed" }
        }
        play.world.step()
        assertTrue(play.body().vy < 0.0, "jumps once on the ground")
    }

    @Test
    fun aJumpReachesTheHeightItsSpeedGives() {
        ground()
        add(
            "Player",
            0.0,
            4.0,
            1.0,
            listOf(ComponentDoc("gameplay:body")),
            listOf(Rule(Condition.Always, listOf(Condition.OnGround), listOf(Action.Jump(15.0)))),
        )
        val play = play()
        play.world.step()
        var highest = play.y()
        repeat(40) {
            play.world.step()
            highest = minOf(highest, play.y())
        }
        // Speed² / (2 × gravity) = 2.25, a little less with one gravity step per moment.
        val height = 4.0 - highest
        assertTrue(height > 2.0 && height < 2.3, "jump height was $height")
    }

    @Test
    fun setSpeedLaunchesABody() {
        add(
            "Player",
            0.0,
            0.0,
            1.0,
            listOf(ComponentDoc("gameplay:body", mapOf("gravity" to 0.0))),
            listOf(Rule(Condition.GameStarts, actions = listOf(Action.SetSpeed(Direction.Right, 6.0), Action.SetSpeed(Direction.Up, 3.0)))),
        )
        val play = play()
        repeat(60) { play.world.step() }

        assertTrue(abs(play.x() - 6.0) < 1e-9, "x was ${play.x()}")
        assertTrue(abs(play.y() + 3.0) < 1e-9, "y was ${play.y()}")
    }
}
