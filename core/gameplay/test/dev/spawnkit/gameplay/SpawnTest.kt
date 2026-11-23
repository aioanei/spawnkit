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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spawning copies of template objects during play (C14): enemies, falling things and shots. */
class SpawnTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private fun add(
        name: String,
        tag: String,
        x: Double = 0.0,
        y: Double = 0.0,
        template: Boolean = false,
        vararg rules: Rule,
    ): String {
        val components =
            listOfNotNull(
                ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y)),
                ComponentDoc("graphics:shape", mapOf("width" to 1.0, "height" to 1.0)),
                ComponentDoc("gameplay:tag", mapOf("tag" to tag)),
                ComponentDoc("gameplay:number"),
                if (template) ComponentDoc("gameplay:template") else null,
            )
        val id = assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddObject(scene, name, components = components))).createdIds.single()
        if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules.toList())))
        return id
    }

    private fun play(): SceneWorld = buildSceneWorld(engine.runtime, engine.playProject(editor.project), scene, engine.programs)

    /** One tick of play, then the requests it made, as players handle them. */
    private fun SceneWorld.tick(times: Int = 1) =
        apply {
            repeat(times) {
                world.step()
                engine.handleRequests(world, editor.project, scene)
            }
        }

    private fun SceneWorld.tagged(tag: String): List<EntityHandle> = engine.gameplay.tagged(world, tag)

    private fun SceneWorld.x(entity: EntityHandle): Double = world.get(entity, engine.graphics.transform)!!.x

    private fun SceneWorld.y(entity: EntityHandle): Double = world.get(entity, engine.graphics.transform)!!.y

    @Test
    fun spawnBlocksRoundTripThroughProgramsAndTheCatalog() {
        val spawns =
            listOf(
                Action.Spawn("enemy", SpawnPlace.Self),
                Action.Spawn("shot", SpawnPlace.Tagged("ship"), Direction.Up, 12.5),
                Action.Spawn("coin", SpawnPlace.Position(3.0, -2.5)),
                Action.Spawn("rock", SpawnPlace.RandomArea(-10, 10, -6, -6), Direction.Down, 4.0),
            )
        val rule = Rule(Condition.Tapped, actions = spawns)

        assertEquals(listOf(RuleItem.Blocks(rule)), RuleBlocks.decompile(RuleBlocks.compile(listOf(rule))))
        for (action in spawns) {
            assertEquals(BlockResult.Created(action), RuleBlockCatalog.action(RuleBlockCatalog.picked(action)))
        }
        assertEquals("Spawn a copy of shot at ship, moving up at 12.5 per second", RuleBlocks.describe(spawns[1]))
        assertEquals("Spawn a copy of rock at a random spot, moving down at 4 per second", RuleBlocks.describe(spawns[3]))
    }

    @Test
    fun templatesAreLeftOutOfPlay() {
        add("Enemy", "enemy", template = true)
        add("Hero", "hero")

        val built = play()

        assertEquals(emptyList(), built.tagged("enemy"))
        assertEquals(1, built.tagged("hero").size)
        assertEquals(2, editor.project.scenes.single().objects.size, "the builder still shows the template")
    }

    @Test
    fun aSpawnedCopyRunsItsOwnRules() {
        add("Enemy", "enemy", template = true, rules = arrayOf(Rule(Condition.Always, actions = listOf(Action.ChangeNumber(Target.Self, 1)))))
        add("Spawner", "spawner", x = 4.0, y = 2.0, rules = arrayOf(Rule(Condition.GameStarts, actions = listOf(Action.Spawn("enemy", SpawnPlace.Self)))))
        val built = play().tick()

        val copy = built.tagged("enemy").single()
        built.tick(3)

        assertEquals(4.0, built.x(copy))
        assertEquals(2.0, built.y(copy))
        assertEquals(3, built.world.get(copy, engine.gameplay.number)!!.value)
        assertNull(built.world.get(copy, engine.gameplay.template), "copies are not templates")
    }

    @Test
    fun copiesMoveInTheirDirection() {
        add("Shot", "shot", template = true)
        add("Gun", "gun", rules = arrayOf(Rule(Condition.GameStarts, actions = listOf(Action.Spawn("shot", SpawnPlace.Position(1.0, 1.0), Direction.Right, 6.0)))))
        val built = play().tick()
        val shot = built.tagged("shot").single()

        built.tick(60)

        assertTrue(abs(built.x(shot) - 7.0) < 1e-9, "moved 6 units in one second: ${built.x(shot)}")
        assertEquals(1.0, built.y(shot))
    }

    @Test
    fun spawningAtARandomSpotRepeatsWithTheSameSeed() {
        add("Rock", "rock", template = true)
        add("Sky", "sky", rules = arrayOf(Rule(Condition.Always, actions = listOf(Action.Spawn("rock", SpawnPlace.RandomArea(-10, 10, -5, 5))))))

        val spots = { built: SceneWorld -> built.tick(5).tagged("rock").map { built.x(it) to built.y(it) } }
        val first = spots(play())
        val second = spots(play())

        assertEquals(5, first.size)
        assertEquals(first, second)
        assertTrue(first.all { (x, y) -> x in -10.0..10.0 && y in -5.0..5.0 })
        assertTrue(first.toSet().size > 1, "spots vary")
    }

    @Test
    fun spawningAMissingTagDoesNothing() {
        add("Spawner", "spawner", rules = arrayOf(Rule(Condition.GameStarts, actions = listOf(Action.Spawn("ghost", SpawnPlace.Self)))))

        val built = play().tick()

        assertEquals(emptyList(), built.tagged("ghost"))
    }
}
