package dev.spawnkit.gameplay

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.editing.buildSceneWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Random numbers and adding one number to another (C14), for dice, loot and tycoon income. */
class NumberBlocksTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private fun numbered(
        tag: String,
        value: Long,
        vararg rules: Rule,
    ): String {
        val id =
            assertIs<OperationResult.Applied>(
                editor.apply(
                    EditOperation.AddObject(
                        scene,
                        tag,
                        components =
                            listOf(
                                ComponentDoc("gameplay:tag", mapOf("tag" to tag)),
                                ComponentDoc("gameplay:number", mapOf("number" to value)),
                            ),
                    ),
                ),
            ).createdIds.single()
        if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules.toList())))
        return id
    }

    private fun SceneWorld.number(id: String): Long = world.get(entity(id)!!, engine.gameplay.number)!!.value

    private fun SceneWorld.run(ticks: Int): SceneWorld = apply { repeat(ticks) { world.step() } }

    @Test
    fun theNewBlocksRoundTripThroughProgramsAndTheCatalog() {
        val random = Action.SetRandomNumber(Target.Self, 1, 6)
        val add = Action.AddNumber(Target.Tagged("coins"), Target.Tagged("income"))
        val rule = Rule(Condition.Tapped, actions = listOf(random, add))

        assertEquals(listOf(RuleItem.Blocks(rule)), RuleBlocks.decompile(RuleBlocks.compile(listOf(rule))))
        for (action in listOf(random, add)) {
            assertEquals(BlockResult.Created(action), RuleBlockCatalog.action(RuleBlockCatalog.picked(action)))
        }
        assertEquals("Set This object's number to a random number from 1 to 6", RuleBlocks.describe(random))
        assertEquals("Add income's number to coins", RuleBlocks.describe(add))
    }

    @Test
    fun aRandomRangeMustRunUpwards() {
        val spec = RuleBlockCatalog.actions.single { it.id == "SetRandomNumber" }

        val invalid = spec.create(mapOf("min" to 7L, "max" to 2L))

        assertTrue(assertIs<BlockResult.Invalid>(invalid).message.contains("smallest"))
    }

    @Test
    fun theSameSeedRollsTheSameNumbers() {
        val dice = numbered("dice", 0, Rule(Condition.Always, actions = listOf(Action.SetRandomNumber(Target.Self, 1, 1_000_000))))

        val rolls = { built: SceneWorld -> (1..5).map { built.run(1).number(dice) } }
        val first = rolls(buildSceneWorld(engine.runtime, editor.project, scene, engine.programs))
        val second = rolls(buildSceneWorld(engine.runtime, editor.project, scene, engine.programs))

        assertEquals(first, second)
        assertTrue(first.all { it in 1..1_000_000 })
        assertNotEquals(1, first.toSet().size, "rolls vary from moment to moment")
    }

    @Test
    fun incomeIsAddedEverySecond() {
        numbered("income", 5)
        val coins =
            numbered("coins", 0, Rule(Condition.EverySeconds(1), actions = listOf(Action.AddNumber(Target.Self, Target.Tagged("income")))))

        val built = buildSceneWorld(engine.runtime, editor.project, scene, engine.programs).run(120)

        assertEquals(10, built.number(coins))
    }
}
