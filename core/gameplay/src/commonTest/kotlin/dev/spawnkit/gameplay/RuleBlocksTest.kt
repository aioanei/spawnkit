package dev.spawnkit.gameplay

import dev.spawnkit.graphics.Shape2D
import dev.spawnkit.graphics.Text2D
import dev.spawnkit.graphics.Transform2D
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.PrepareResult
import dev.spawnkit.program.Program
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.Trigger
import dev.spawnkit.program.prepareProgram
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleBlocksTest {
    private val engine = StandardEngine()

    private val everyCondition: List<Condition> =
        listOf(
            Condition.Always,
            Condition.GameStarts,
            Condition.EverySeconds(3),
            Condition.Touching("coin"),
            Condition.Tapped,
            Condition.KeyHeld(StandardActions.RIGHT),
            Condition.KeyPressed(StandardActions.JUMP),
            Condition.NumberIs(Target.Self, Comparison.AtLeast, 10),
            Condition.NumberIs(Target.Tagged("score"), Comparison.Equal, 3),
            Condition.CountIs("coin", Comparison.Equal, 0),
        )

    private val everyAction: List<Action> =
        listOf(
            Action.Move(Direction.Right, 4.5),
            Action.Move(Direction.Up, 2.0),
            Action.ChangeNumber(Target.Tagged("score"), 1),
            Action.ChangeNumber(Target.Self, -2),
            Action.SetNumber(Target.Self, 0),
            Action.SetText(Target.Self, "Ready!"),
            Action.SetText(Target.Tagged("score"), "Game over"),
            Action.ShowNumber(Target.Tagged("score"), "Score: "),
            Action.RemoveSelf,
        )

    @Test
    fun everyBlockRoundTripsThroughProgramHandlers() {
        val rules =
            everyCondition.map { Rule(it, actions = listOf(Action.RemoveSelf)) } +
                everyAction.map { Rule(Condition.Always, actions = listOf(it)) } +
                Rule(Condition.Touching("player"), listOf(Condition.KeyHeld(StandardActions.DOWN), Condition.Tapped), everyAction)
        val handlers = RuleBlocks.compile(rules)
        assertEquals(rules.map { RuleItem.Blocks(it) }, RuleBlocks.decompile(handlers))
    }

    @Test
    fun everyBlockPreparesAgainstTheStandardEngine() {
        val rules = everyCondition.map { Rule(it, actions = everyAction) }
        val result = prepareProgram(Program("blocks", handlers = RuleBlocks.compile(rules)), engine.host)
        assertIs<PrepareResult.Ready>(result, (result as? PrepareResult.Invalid)?.diagnostics.toString())
    }

    @Test
    fun handlersThatAreNotBlocksStayAdvancedAndUnchanged() {
        val custom =
            Handler(
                Trigger.EveryTick,
                listOf(Stmt.Destroy(Expr.Self(SourceRef("mine", "mine")), SourceRef("gone", "gone"))),
                SourceRef("custom", "My rule"),
            )
        val blocks = RuleBlocks.compile(listOf(Rule(Condition.Tapped, actions = listOf(Action.RemoveSelf))))
        // A block-shaped handler whose IDs were changed by another tool is not trusted as blocks either.
        val edited = blocks.single().copy(source = SourceRef("other", "other"))
        val items = RuleBlocks.decompile(listOf(custom, blocks.single(), edited))
        assertEquals(RuleItem.Advanced(custom), items[0])
        assertIs<RuleItem.Blocks>(items[1])
        assertEquals(RuleItem.Advanced(edited), items[2])
        // Recompiling renumbers block rules by position and keeps advanced rules exactly.
        val renumbered = RuleBlocks.compile(listOf(Rule(Condition.Always), Rule(Condition.Tapped, actions = listOf(Action.RemoveSelf))))[1]
        assertEquals(listOf(custom, renumbered, edited), RuleBlocks.compileItems(items))
    }

    @Test
    fun blockNodeIdsLocateProblemsAndLabelsDescribeBlocks() {
        val rule = Rule(Condition.Touching("player"), listOf(Condition.Tapped), listOf(Action.ChangeNumber(Target.Tagged("score"), 1)))
        val handler = RuleBlocks.compile(listOf(Rule(Condition.Always, actions = listOf(Action.RemoveSelf)), rule))[1]
        val location = RuleBlocks.locate("rule1.do0")
        assertEquals(BlockLocation(rule = 1, part = BlockPart.Action, index = 0), location)
        assertEquals(BlockLocation(1, BlockPart.Condition, 1), RuleBlocks.locate("rule1.if1"))
        assertNull(RuleBlocks.locate("custom"))
        assertNull(RuleBlocks.locate("rule2147483648.do0"))
        assertNull(RuleBlocks.locate("rule0.if2147483648"))
        assertEquals("Touching player", RuleBlocks.describe(rule.condition))
        assertEquals("Add 1 to score", RuleBlocks.describe(rule.actions.single()))
        assertEquals("rule1", handler.source.nodeId)
    }

    private fun World.thing(
        x: Double,
        tag: String,
        vararg rules: Rule,
    ): EntityHandle =
        spawn().also {
            set(it, engine.graphics.transform, Transform2D(x, 0.0))
            set(it, engine.graphics.shape, Shape2D(width = 0.5, height = 0.5))
            set(it, engine.gameplay.tag, Tag(tag))
            if (rules.isNotEmpty()) {
                val program = Program("rules.$tag.$x", handlers = RuleBlocks.compile(rules.toList()))
                val prepared = assertIs<PrepareResult.Ready>(prepareProgram(program, engine.host)).program
                set(it, engine.programs.behavior, engine.programs.instantiate(prepared))
            }
        }

    @Test
    fun aScoreAndCollectGameBuiltFromBlocksPlays() {
        val world = engine.runtime.createWorld()
        val player =
            world.thing(
                0.0,
                "player",
                Rule(Condition.KeyHeld(StandardActions.RIGHT), actions = listOf(Action.Move(Direction.Right, 30.0))),
            )
        val score = world.spawn()
        world.set(score, engine.gameplay.tag, Tag("score"))
        world.set(score, engine.gameplay.number, NumberValue(0))
        world.set(score, engine.graphics.text, Text2D("Score: 0"))
        val collect =
            Rule(
                Condition.Touching("player"),
                actions =
                    listOf(
                        Action.ChangeNumber(Target.Tagged("score"), 1),
                        Action.ShowNumber(Target.Tagged("score"), "Score: "),
                        Action.RemoveSelf,
                    ),
            )
        world.thing(2.0, "coin", collect)
        world.thing(3.0, "coin", collect)
        repeat(30) {
            world.submitInput(InputFrame(down = setOf(engine.actions.getValue(StandardActions.RIGHT))))
            world.step()
        }
        assertEquals(2L, world.get(score, engine.gameplay.number)?.value)
        assertEquals("Score: 2", world.get(score, engine.graphics.text)?.text)
        assertTrue(checkNotNull(world.get(player, engine.graphics.transform)).x > 3.0, "Moved at 30 units per second")
        assertNull(world.get(player, engine.programs.behavior)?.fault)
    }

    @Test
    fun aTextBlockUpdatesTheTargetLabel() {
        val world = engine.runtime.createWorld()
        val label = world.spawn()
        world.set(label, engine.gameplay.tag, Tag("label"))
        world.set(label, engine.graphics.text, Text2D("Ready"))
        world.thing(
            0.0,
            "button",
            Rule(Condition.GameStarts, actions = listOf(Action.SetText(Target.Tagged("label"), "Go!"))),
        )
        world.step()
        assertEquals("Go!", world.get(label, engine.graphics.text)?.text)
    }

    @Test
    fun timedConditionsFireOnSchedule() {
        val world = engine.runtime.createWorld()
        val counter = world.spawn()
        world.set(counter, engine.gameplay.number, NumberValue(0))
        val rules =
            RuleBlocks.compile(
                listOf(
                    Rule(Condition.GameStarts, actions = listOf(Action.SetNumber(Target.Self, 100))),
                    Rule(Condition.EverySeconds(2), actions = listOf(Action.ChangeNumber(Target.Self, 1))),
                ),
            )
        val prepared = assertIs<PrepareResult.Ready>(prepareProgram(Program("timer", handlers = rules), engine.host)).program
        world.set(counter, engine.programs.behavior, engine.programs.instantiate(prepared))
        world.step()
        assertEquals(100L, world.get(counter, engine.gameplay.number)?.value, "Game start runs on the first tick")
        repeat(299) { world.step() }
        assertEquals(102L, world.get(counter, engine.gameplay.number)?.value, "Every 2 seconds over 5 seconds: twice")
    }
}
