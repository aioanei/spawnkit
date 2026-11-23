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
        val id =
            assertIs<OperationResult.Applied>(
                editor.apply(EditOperation.AddObject(scene, name, components = components)),
            ).createdIds.single()
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
    fun spawnArgumentsAreWrittenTheSameOnEveryPlatform() {
        // Rules saved on one platform must compile to the same text on another (Kotlin/JS writes 14.0 as 14).
        val header = SpawnRequest.header(Action.Spawn("shot", SpawnPlace.Position(2.0, -0.0), Direction.Up, 14.0))
        assertEquals("""{"tag":"shot","place":"position","x":2.0,"y":-0.0,"direction":"Up","speed":14.0}""", header)
        assertEquals(
            """{"tag":"rock","place":"self","speed":2.5}""",
            SpawnRequest.header(Action.Spawn("rock", SpawnPlace.Self, null, 2.5)),
        )
    }

    @Test
    fun templatesAreLeftOutOfPlay() {
        add("Enemy", "enemy", template = true)
        add("Hero", "hero")

        val built = play()

        assertEquals(emptyList(), built.tagged("enemy"))
        assertEquals(1, built.tagged("hero").size)
        assertEquals(
            2,
            editor.project.scenes
                .single()
                .objects.size,
            "the builder still shows the template",
        )
    }

    @Test
    fun aSpawnedCopyRunsItsOwnRules() {
        add(
            "Enemy",
            "enemy",
            template = true,
            rules = arrayOf(Rule(Condition.Always, actions = listOf(Action.ChangeNumber(Target.Self, 1)))),
        )
        add(
            "Spawner",
            "spawner",
            x = 4.0,
            y = 2.0,
            rules = arrayOf(Rule(Condition.GameStarts, actions = listOf(Action.Spawn("enemy", SpawnPlace.Self)))),
        )
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
        add(
            "Gun",
            "gun",
            rules =
                arrayOf(
                    Rule(Condition.GameStarts, actions = listOf(Action.Spawn("shot", SpawnPlace.Position(1.0, 1.0), Direction.Right, 6.0))),
                ),
        )
        val built = play().tick()
        val shot = built.tagged("shot").single()

        built.tick(60)

        assertTrue(abs(built.x(shot) - 7.0) < 1e-9, "moved 6 units in one second: ${built.x(shot)}")
        assertEquals(1.0, built.y(shot))
    }

    @Test
    fun spawningAtARandomSpotRepeatsWithTheSameSeed() {
        add("Rock", "rock", template = true)
        add(
            "Sky",
            "sky",
            rules = arrayOf(Rule(Condition.Always, actions = listOf(Action.Spawn("rock", SpawnPlace.RandomArea(-10, 10, -5, 5))))),
        )

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

    @Test
    fun copiesAppearAtTheWorldPositionOfAChildSpawnerOrTaggedChild() {
        add("Shot", "shot", template = true)
        val group = add("Group", "group", x = 10.0, y = 20.0)
        editor.apply(EditOperation.SetProperty(scene, group, "graphics:transform", "scaleX", 2.0))
        editor.apply(EditOperation.SetProperty(scene, group, "graphics:transform", "scaleY", 3.0))
        val gun =
            add(
                "Gun",
                "gun",
                x = 1.0,
                y = 2.0,
                rules =
                    arrayOf(
                        Rule(
                            Condition.GameStarts,
                            actions = listOf(Action.Spawn("shot", SpawnPlace.Self), Action.Spawn("shot", SpawnPlace.Tagged("gun"))),
                        ),
                    ),
            )
        editor.apply(EditOperation.ReparentObject(scene, gun, group))

        val built = play().tick()

        assertEquals(listOf(12.0 to 26.0, 12.0 to 26.0), built.tagged("shot").map { built.x(it) to built.y(it) })
    }

    @Test
    fun aCopyKeepsItsChildrenAndTheirRulesAndFitsTheObjectLimitAsAWhole() {
        val parent = add("Enemy", "enemy", template = true)
        val child =
            assertIs<OperationResult.Applied>(
                editor.apply(
                    EditOperation.AddObject(
                        scene,
                        "Counter",
                        parent = parent,
                        components = listOf(ComponentDoc("gameplay:number")),
                    ),
                ),
            ).createdIds.single()
        editor.apply(
            EditOperation.SetRules(
                scene,
                child,
                RuleBlocks.compile(listOf(Rule(Condition.Always, actions = listOf(Action.ChangeNumber(Target.Self, 1))))),
            ),
        )
        val built = play()
        val request = SpawnRequest("enemy", 3.0, 4.0, 0.0, 0.0)
        val copy = engine.spawn(built.world, editor.project, scene, request)!!
        val counter = built.world.children(copy).single()
        built.tick(2)

        assertEquals(2L, built.world.get(counter, engine.gameplay.number)?.value)
        assertEquals(child, built.objectId(counter))
        assertEquals(3.0, built.x(copy))
        repeat(StandardEngine.MAX_OBJECTS - built.world.entityCount - 1) { built.world.spawn() }
        val before = built.world.entityCount

        assertNull(engine.spawn(built.world, editor.project, scene, request), "a partial copy must never be made")
        assertEquals(before, built.world.entityCount)
    }

    @Test
    fun spawningUsesEarlierMovesInTheSameRule() {
        add("Shot", "shot", template = true)
        add(
            "Gun",
            "gun",
            x = 1.0,
            y = 2.0,
            rules =
                arrayOf(
                    Rule(
                        Condition.GameStarts,
                        actions =
                            listOf(
                                Action.Move(Direction.Right, 60.0),
                                Action.Spawn("shot", SpawnPlace.Self),
                                Action.Spawn("shot", SpawnPlace.Tagged("gun")),
                            ),
                    ),
                ),
        )

        val built = play().tick()

        assertEquals(listOf(2.0 to 2.0, 2.0 to 2.0), built.tagged("shot").map { built.x(it) to built.y(it) })
    }
}
