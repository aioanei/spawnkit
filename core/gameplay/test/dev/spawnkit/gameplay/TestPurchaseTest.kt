package dev.spawnkit.gameplay

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.buildSceneWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Test purchases (C12b): a rule asks to buy, and the player grants the item through a number after confirming. */
class TestPurchaseTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    @Test
    fun theBlockRoundTripsThroughProgramsAndTheCatalog() {
        val buy = Action.TestPurchase("100 coins", 99, Target.Tagged("coins"), 100)
        val rule = Rule(Condition.Tapped, actions = listOf(buy))

        assertEquals(listOf(RuleItem.Blocks(rule)), RuleBlocks.decompile(RuleBlocks.compile(listOf(rule))))
        assertEquals(BlockResult.Created(buy), RuleBlockCatalog.action(RuleBlockCatalog.picked(buy)))
        assertEquals("Test purchase \"100 coins\" for $0.99, then add 100 to coins", RuleBlocks.describe(buy))
    }

    @Test
    fun aPurchaseIsRequestedAndGrantedOnlyWhenConfirmed() {
        fun add(
            name: String,
            tag: String,
            rules: List<Rule> = emptyList(),
        ): String {
            val id =
                assertIs<OperationResult.Applied>(
                    editor.apply(
                        EditOperation.AddObject(
                            scene,
                            name,
                            components = listOf(ComponentDoc("gameplay:tag", mapOf("tag" to tag)), ComponentDoc("gameplay:number")),
                        ),
                    ),
                ).createdIds.single()
            if (rules.isNotEmpty()) editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(rules)))
            return id
        }
        add("Coins", "coins")
        add("Shop item", "item", listOf(Rule(Condition.GameStarts, actions = listOf(Action.TestPurchase("Big bag", 199, Target.Tagged("coins"), 50)))))
        val world = buildSceneWorld(engine.runtime, editor.project, scene, engine.programs).world
        world.step()

        val purchase = engine.takeRequests(world).purchases.single()
        assertEquals("Big bag", purchase.action.product)
        val coins = engine.gameplay.tagged(world, "coins").single()
        assertEquals(0L, world.get(coins, engine.gameplay.number)!!.value, "nothing is granted before the creator's player confirms")

        engine.grant(world, purchase)
        assertEquals(50L, world.get(coins, engine.gameplay.number)!!.value)
    }
}
