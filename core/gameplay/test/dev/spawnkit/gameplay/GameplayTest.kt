package dev.spawnkit.gameplay

import dev.spawnkit.graphics.Shape2D
import dev.spawnkit.graphics.Text2D
import dev.spawnkit.graphics.Transform2D
import dev.spawnkit.program.BinaryOp
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.Expr
import dev.spawnkit.program.FaultKind
import dev.spawnkit.program.Handler
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.PrepareResult
import dev.spawnkit.program.Program
import dev.spawnkit.program.ProgramInstance
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.StateField
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.TextValue
import dev.spawnkit.program.Trigger
import dev.spawnkit.program.prepareProgram
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.Pointer
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Program blocks as a visual rule editor would produce them. */
private object B {
    private fun s(id: String) = SourceRef(id, id)

    fun num(v: Double) = Expr.Literal(DecimalValue(v), s("lit"))

    fun int(v: Long) = Expr.Literal(IntegerValue(v), s("lit"))

    fun text(v: String) = Expr.Literal(TextValue(v), s("lit"))

    fun self() = Expr.Self(s("self"))

    fun get(
        target: Expr,
        property: String,
    ) = Expr.GetProperty(target, property, s("get:$property"))

    fun call(
        function: String,
        vararg args: Expr,
    ) = Expr.CallHost(function, args.toList(), s("call:$function"))

    fun op(
        op: BinaryOp,
        left: Expr,
        right: Expr,
    ) = Expr.Binary(op, left, right, s("op"))

    fun set(
        target: Expr,
        property: String,
        value: Expr,
    ) = Stmt.SetProperty(target, property, value, s("set:$property"))

    fun ifThen(
        condition: Expr,
        vararg then: Stmt,
    ) = Stmt.If(condition, then.toList(), emptyList(), s("if"))

    fun destroy(target: Expr) = Stmt.Destroy(target, s("destroy"))

    fun everyTick(vararg body: Stmt) = Handler(Trigger.EveryTick, body.toList(), s("rule"))
}

class GameplayTest {
    private val engine = StandardEngine(seed = 7)
    private var programs = 0

    private fun World.thing(
        x: Double,
        y: Double,
        tag: String = "",
        width: Double = 1.0,
        height: Double = 1.0,
    ): EntityHandle =
        spawn().also {
            set(it, engine.graphics.transform, Transform2D(x, y))
            set(it, engine.graphics.shape, Shape2D(width = width, height = height))
            if (tag.isNotEmpty()) set(it, engine.gameplay.tag, Tag(tag))
        }

    private fun World.attach(
        entity: EntityHandle,
        vararg handlers: Handler,
        state: List<StateField> = emptyList(),
    ) {
        // Program IDs are unique within an engine, as each object's rules are separate programs.
        val program = Program("rules${programs++}", state = state, handlers = handlers.toList())
        val prepared =
            when (val result = prepareProgram(program, engine.host)) {
                is PrepareResult.Ready -> result.program
                is PrepareResult.Invalid -> error("Unexpected diagnostics: ${result.diagnostics}")
            }
        set(entity, engine.programs.behavior, engine.programs.instantiate(prepared))
    }

    private fun World.rules(entity: EntityHandle): ProgramInstance = checkNotNull(get(entity, engine.programs.behavior))

    private fun World.number(entity: EntityHandle) = checkNotNull(get(entity, engine.gameplay.number)).value

    /** Player moves right while "right" is held; each coin touching the player adds a point, updates the label and disappears. */
    private fun coinGame(): Pair<World, EntityHandle> {
        val world = engine.runtime.createWorld()
        val player = world.thing(0.0, 0.0, "player")
        world.attach(
            player,
            B.everyTick(
                B.ifThen(
                    B.call("isDown", B.text(StandardActions.RIGHT)),
                    B.set(B.self(), "x", B.op(BinaryOp.Add, B.get(B.self(), "x"), B.num(0.5))),
                ),
            ),
        )
        val score = world.spawn()
        world.set(score, engine.gameplay.tag, Tag("score"))
        world.set(score, engine.gameplay.number, NumberValue(0))
        world.set(score, engine.graphics.text, Text2D("Score: 0"))
        for (x in listOf(2.0, 4.0)) {
            val coin = world.thing(x, 0.0, "coin", width = 0.5, height = 0.5)
            val scoreObject = B.call("firstTagged", B.text("score"))
            val next = B.op(BinaryOp.Add, B.get(scoreObject, "number"), B.int(1))
            world.attach(
                coin,
                B.everyTick(
                    B.ifThen(
                        B.call("touching", B.text("player")),
                        B.set(scoreObject, "number", next),
                        B.set(
                            scoreObject,
                            "text_text",
                            B.op(BinaryOp.Add, B.text("Score: "), B.call("textOf", B.get(scoreObject, "number"))),
                        ),
                        B.destroy(B.self()),
                    ),
                ),
            )
        }
        return world to score
    }

    private fun World.holdRight() = submitInput(InputFrame(down = setOf(engine.actions.getValue(StandardActions.RIGHT))))

    @Test
    fun aCoinCollectGameAuthoredAsRulesPlaysDeterministically() {
        val (world, score) = coinGame()
        repeat(12) {
            world.holdRight()
            world.step()
        }
        assertEquals(2L, world.number(score))
        assertEquals("Score: 2", world.get(score, engine.graphics.text)?.text)
        assertEquals(0, engine.gameplay.countTagged(world, "coin"), "Collected coins are removed")
        world.entities(engine.programs.behavior).forEach { assertNull(world.rules(it).fault) }
    }

    @Test
    fun theCoinGameReplaysIdenticallyFromACheckpoint() {
        val (live, _) = coinGame()
        repeat(3) {
            live.holdRight()
            live.step()
        }
        val snapshot = live.snapshot()
        repeat(9) {
            live.holdRight()
            live.step()
        }
        val replay = engine.runtime.restore(snapshot)
        repeat(9) {
            replay.holdRight()
            replay.step()
        }
        assertEquals(live.stateHash(), replay.stateHash())
    }

    @Test
    fun tappingEarnsCoinsAndPassiveIncomeArrivesEverySecond() {
        val world = engine.runtime.createWorld()
        val bank = world.spawn()
        world.set(bank, engine.gameplay.tag, Tag("bank"))
        world.set(bank, engine.gameplay.number, NumberValue(0))
        val bankObject = B.call("firstTagged", B.text("bank"))

        fun earn(amount: Long) = B.set(bankObject, "number", B.op(BinaryOp.Add, B.get(bankObject, "number"), B.int(amount)))
        val button = world.thing(0.0, 0.0, "button", width = 2.0, height = 2.0)
        world.attach(button, B.everyTick(B.ifThen(B.call("tapped"), earn(1))))
        // Income lands once each second, after every 60 ticks of play.
        world.attach(
            bank,
            B.everyTick(B.ifThen(B.op(BinaryOp.Equal, B.op(BinaryOp.Remainder, B.call("tick"), B.int(60)), B.int(59)), earn(5))),
        )
        val inside = Pointer(1, 0.5, -0.5, pressed = true)
        val frames =
            listOf(
                InputFrame(pointers = listOf(inside)), // tap
                InputFrame(pointers = listOf(inside)), // held: not a new tap
                InputFrame(), // released
                InputFrame(pointers = listOf(Pointer(1, 5.0, 5.0, pressed = true))), // outside the button
                InputFrame(), // released
                InputFrame(pointers = listOf(Pointer(2, -0.9, 0.9, pressed = true))), // tap near a corner
            )
        for (frame in frames) {
            world.submitInput(frame)
            world.step()
        }
        assertEquals(2L, world.number(bank), "Two taps")
        world.submitInput(InputFrame())
        repeat(114) { world.step() }
        assertEquals(120L, world.tick)
        assertEquals(12L, world.number(bank), "Two taps plus two seconds of income")
        assertNull(world.rules(button).fault)
        assertNull(world.rules(bank).fault)
    }

    @Test
    fun touchingUsesScaledBoundsIncludingEdgesAndIgnoresSelfAndShapelessObjects() {
        val world = engine.runtime.createWorld()
        val probe = world.thing(0.0, 0.0, "wall")
        val near = world.thing(1.0, 0.0, "wall") // edges meet at x = 0.5
        val far = world.thing(3.0, 0.0, "far")
        val parent = world.spawn()
        world.set(parent, engine.graphics.transform, Transform2D(x = 10.0, scaleX = 4.0, scaleY = 4.0))
        val scaled = world.thing(-1.5, 0.0, "scaled") // world centre 4, half-width 2: spans 2..6
        world.setParent(scaled, parent)
        val ghost = world.spawn()
        world.set(ghost, engine.graphics.transform, Transform2D())
        world.set(ghost, engine.gameplay.tag, Tag("ghost"))
        assertTrue(engine.gameplay.touching(world, probe, "wall"), "Meeting edges count as touching")
        assertFalse(engine.gameplay.touching(world, far, "far"), "An object never touches itself")
        assertFalse(engine.gameplay.touching(world, probe, "far"))
        assertTrue(engine.gameplay.touching(world, far, "scaled"), "Parent position and scale apply")
        assertFalse(engine.gameplay.touching(world, probe, "ghost"), "Objects without a shape cannot be touched")
        world.set(near, engine.graphics.shape, Shape2D(visible = false))
        assertTrue(engine.gameplay.touching(world, probe, "wall"), "Hidden shapes still collide, like invisible walls")
    }

    @Test
    fun randomNumbersAreSeededCheckpointedAndRangeChecked() {
        fun roller(world: World): EntityHandle {
            val dice = world.spawn()
            world.set(dice, engine.gameplay.number, NumberValue(0))
            world.attach(dice, B.everyTick(B.set(B.self(), "number", B.call("random", B.int(1), B.int(6)))))
            return dice
        }
        val first = engine.runtime.createWorld()
        val second = engine.runtime.createWorld()
        val a = roller(first)
        val b = roller(second)
        val rolls = mutableListOf<Long>()
        repeat(20) {
            first.step()
            second.step()
            assertEquals(first.number(a), second.number(b), "Same seed, same rolls")
            rolls += first.number(a)
        }
        assertTrue(rolls.all { it in 1L..6L } && rolls.toSet().size > 1, "Rolls stay in range and vary: $rolls")
        val restored = engine.runtime.restore(first.snapshot())
        first.step()
        restored.step()
        assertEquals(first.stateHash(), restored.stateHash(), "The generator position is checkpointed")

        val broken = engine.runtime.createWorld()
        val bad = broken.spawn()
        broken.set(bad, engine.gameplay.number, NumberValue(0))
        broken.attach(bad, B.everyTick(B.set(B.self(), "number", B.call("random", B.int(6), B.int(1)))))
        broken.step()
        assertEquals(FaultKind.HostFailure, broken.rules(bad).fault?.kind)
    }

    @Test
    fun textOfFormatsNumbersAndMissingTaggedObjectsFaultClearly() {
        val world = engine.runtime.createWorld()
        val label = world.spawn()
        world.set(label, engine.graphics.text, Text2D())
        world.attach(label, B.everyTick(B.set(B.self(), "text_text", B.call("textOf", B.int(-42)))))
        world.step()
        assertEquals("-42", world.get(label, engine.graphics.text)?.text)

        val lost = world.spawn()
        world.set(lost, engine.gameplay.number, NumberValue(0))
        world.attach(lost, B.everyTick(B.set(B.call("firstTagged", B.text("nobody")), "number", B.int(1))))
        world.step()
        val fault = world.rules(lost).fault
        assertEquals(FaultKind.HostFailure, fault?.kind)
        assertTrue(fault?.message?.contains("nobody") == true, fault?.message)
    }

    @Test
    fun theStandardEngineExposesPresentationAndGameplayToRulesAndInspectors() {
        val properties = engine.host.propertyCatalog.map { it.name }
        for (name in listOf("x", "y", "rotation", "shape_width", "shape_visible", "text_text", "text_size", "tag", "number")) {
            assertTrue(name in properties, "Rules can use '$name' ($properties)")
        }
        val functions = engine.host.functionCatalog.map { it.name }
        for (name in listOf("touching", "tapped", "firstTagged", "countTagged", "textOf", "random", "tick", "isDown", "wasPressed")) {
            assertTrue(name in functions, "Rules can call '$name'")
        }
        assertEquals(setOf("input:left", "input:right", "input:up", "input:down", "input:jump"), engine.actions.keys)
        val components = engine.runtime.catalog.map { it.component.id }
        assertTrue(components.containsAll(listOf("graphics:transform", "graphics:text", "gameplay:tag", "gameplay:number")))
        // Each world gets a fresh generator from the seed.
        assertEquals(
            engine.runtime
                .createWorld()
                .resource(engine.gameplay.random)
                .nextLong(),
            engine.runtime
                .createWorld()
                .resource(engine.gameplay.random)
                .nextLong(),
        )
    }
}
