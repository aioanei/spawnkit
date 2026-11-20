package dev.spawnkit.program

import dev.spawnkit.program.P.assign
import dev.spawnkit.program.P.bool
import dev.spawnkit.program.P.call
import dev.spawnkit.program.P.declare
import dev.spawnkit.program.P.effect
import dev.spawnkit.program.P.emit
import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.get
import dev.spawnkit.program.P.host
import dev.spawnkit.program.P.ifThen
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.loop
import dev.spawnkit.program.P.onEvent
import dev.spawnkit.program.P.op
import dev.spawnkit.program.P.ret
import dev.spawnkit.program.P.s
import dev.spawnkit.program.P.self
import dev.spawnkit.program.P.setProp
import dev.spawnkit.program.P.setState
import dev.spawnkit.program.P.state
import dev.spawnkit.program.P.v
import dev.spawnkit.program.P.wait
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramExecutionTest {
    private class Run(
        val fixture: ProgramFixture,
        val world: World,
        val entity: EntityHandle,
    ) {
        val instance: ProgramInstance get() = checkNotNull(world.get(entity, fixture.feature.behavior))
    }

    /** Attaches [program] to a fresh entity that has value=[value] and score=0. */
    private fun run(
        program: Program,
        value: Long = 1,
        fixture: ProgramFixture = ProgramFixture(),
    ): Run {
        val world = fixture.runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, fixture.value, value)
        world.set(entity, fixture.score, 0L)
        world.set(entity, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(program)))
        return Run(fixture, world, entity)
    }

    @Test
    fun functionsLoopsListsAndHostCallsCompute() {
        // total = sum of bonus(i) for i in [1, 2, 3] collected into a list = 3 + 6 + 9.
        val program =
            Program(
                "compute",
                state = listOf(field("total", IntegerValue(0))),
                functions =
                    listOf(
                        FunctionDef(
                            "triple",
                            listOf(Parameter("n", IntegerType)),
                            IntegerType,
                            listOf(ret(host("bonus", v("n")))),
                            s("fn:triple"),
                        ),
                    ),
                handlers =
                    listOf(
                        everyTick(
                            declare("items", Expr.ListOf(IntegerType, emptyList(), s("list"))),
                            declare("i", int(1)),
                            loop(
                                op(BinaryOp.LessOrEqual, v("i"), int(3)),
                                listOf(
                                    assign("items", Expr.Append(v("items"), call("triple", v("i")), s("append"))),
                                    assign("i", op(BinaryOp.Add, v("i"), int(1))),
                                ),
                            ),
                            declare("sum", int(0)),
                            P.each("n", v("items"), listOf(assign("sum", op(BinaryOp.Add, v("sum"), v("n"))))),
                            setState("total", op(BinaryOp.Add, state("total"), v("sum"))),
                        ),
                    ),
            )
        val run = run(program)
        run.world.step()
        run.world.step()
        assertNull(run.instance.fault)
        assertEquals(IntegerValue(36), run.instance.state("total"))
    }

    @Test
    fun eventsAreDeliveredOnTheNextTickToEveryHandlingInstance() {
        val fixture = ProgramFixture()
        val emitter = Program("emitter", handlers = listOf(everyTick(emit("collected", get(self(), "value")))))
        val listener =
            Program(
                "listener",
                handlers =
                    listOf(
                        onEvent(
                            "collected",
                            setProp(self(), "score", op(BinaryOp.Add, get(self(), "score"), v("payload"))),
                        ),
                    ),
            )
        val run = run(emitter, value = 7, fixture = fixture)
        val other = run.world.spawn()
        run.world.set(other, fixture.score, 0L)
        run.world.set(other, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(listener)))
        run.world.step()
        assertEquals(0L, run.world.get(other, fixture.score), "Emission is delivered on the following tick")
        run.world.step()
        assertEquals(7L, run.world.get(other, fixture.score))
        run.world.step()
        assertEquals(14L, run.world.get(other, fixture.score))
    }

    @Test
    fun waitSuspendsAndResumesWithLocalsAndCallFrames() {
        val program =
            Program(
                "waiter",
                state = listOf(field("log", IntegerValue(0)), field("started", BoolValue(false))),
                functions =
                    listOf(
                        FunctionDef(
                            "pause",
                            listOf(Parameter("n", IntegerType)),
                            IntegerType,
                            listOf(
                                declare("before", op(BinaryOp.Multiply, v("n"), int(10))),
                                wait(v("n")),
                                ret(op(BinaryOp.Add, v("before"), int(1))),
                            ),
                            s("fn:pause"),
                        ),
                    ),
                handlers =
                    listOf(
                        everyTick(
                            ifThen(
                                op(BinaryOp.Equal, state("started"), bool(false)),
                                listOf(setState("started", bool(true)), setState("log", call("pause", int(2)))),
                            ),
                        ),
                    ),
            )
        val run = run(program)
        run.world.step()
        assertEquals(1, run.instance.suspendedCount)
        assertEquals(BoolValue(true), run.instance.state("started"), "Work before the wait commits")
        run.world.step()
        assertEquals(IntegerValue(0), run.instance.state("log"))
        run.world.step()
        assertEquals(0, run.instance.suspendedCount)
        assertEquals(IntegerValue(21), run.instance.state("log"))
    }

    @Test
    fun runawayLoopFaultsAndKeepsEarlierCommittedSegmentsButDiscardsItsOwnWork() {
        val program =
            Program(
                "runaway",
                state = listOf(field("ticks", IntegerValue(0))),
                handlers =
                    listOf(
                        everyTick(
                            setState("ticks", op(BinaryOp.Add, state("ticks"), int(1))),
                            setProp(self(), "score", state("ticks")),
                            effect("analytics", state("ticks")),
                            ifThen(
                                op(BinaryOp.GreaterOrEqual, state("ticks"), int(3)),
                                listOf(loop(bool(true, id = "block:forever"), emptyList(), id = "block:forever")),
                            ),
                        ),
                    ),
            )
        val run = run(program)
        repeat(4) { run.world.step() }
        val fault = assertNotNull(run.instance.fault)
        assertEquals(FaultKind.FuelExhausted, fault.kind)
        assertEquals("block:forever", fault.source.nodeId)
        assertEquals(3L, fault.tick)
        assertEquals(IntegerValue(2), run.instance.state("ticks"))
        assertEquals(2L, run.world.get(run.entity, run.fixture.score))
        assertEquals(listOf("0.0:0:0", "0.0:1:0"), run.instance.pendingEffects.map { it.id })
        assertTrue(run.world.isAlive(run.entity) && !run.world.isFaulted, "A program fault never faults the world")
    }

    @Test
    fun unboundedRecursionFaultsAtTheCallDepthLimit() {
        val program =
            Program(
                "recursion",
                functions =
                    listOf(
                        FunctionDef(
                            "down",
                            listOf(Parameter("n", IntegerType)),
                            IntegerType,
                            listOf(ret(call("down", op(BinaryOp.Add, v("n"), int(1)), id = "block:recurse"))),
                            s("fn:down"),
                        ),
                    ),
                handlers = listOf(everyTick(P.eval(call("down", int(0))))),
            )
        val run = run(program)
        run.world.step()
        val fault = assertNotNull(run.instance.fault)
        assertEquals(FaultKind.CallDepthExceeded, fault.kind)
        assertEquals("block:recurse", fault.source.nodeId)
    }

    @Test
    fun unboundedListGrowthFaultsOnAllocationLimits() {
        val program =
            Program(
                "growth",
                handlers =
                    listOf(
                        everyTick(
                            declare("items", Expr.ListOf(IntegerType, emptyList(), s("list"))),
                            loop(bool(true), listOf(assign("items", Expr.Append(v("items"), int(1), s("block:append"))))),
                        ),
                    ),
            )
        val run = run(program)
        run.world.step()
        val fault = assertNotNull(run.instance.fault)
        assertEquals(FaultKind.AllocationExceeded, fault.kind)
        assertEquals("block:append", fault.source.nodeId)
    }

    @Test
    fun failingHostCallRollsBackStatePropertiesEventsAndEffects() {
        val fixture = ProgramFixture()
        val program =
            Program(
                "host-failure",
                state = listOf(field("x", IntegerValue(0))),
                handlers =
                    listOf(
                        everyTick(
                            setState("x", int(1)),
                            setProp(self(), "value", int(99)),
                            emit("ping", null),
                            effect("analytics", int(5)),
                            P.eval(host("bonus", int(-1), id = "block:bonus")),
                        ),
                    ),
            )
        val listener = Program("listener", handlers = listOf(onEvent("ping", setProp(self(), "score", int(1)))))
        val run = run(program, value = 4, fixture = fixture)
        val other = run.world.spawn()
        run.world.set(other, fixture.score, 0L)
        run.world.set(other, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(listener)))
        run.world.step()
        run.world.step()
        val fault = assertNotNull(run.instance.fault)
        assertEquals(FaultKind.HostFailure, fault.kind)
        assertEquals("block:bonus", fault.source.nodeId)
        assertTrue(fault.message.contains("positive"), fault.message)
        assertEquals(IntegerValue(0), run.instance.state("x"))
        assertEquals(4L, run.world.get(run.entity, fixture.value))
        assertEquals(emptyList(), run.instance.pendingEffects)
        assertEquals(0L, run.world.get(other, fixture.score), "A rolled-back event is never delivered")
    }

    @Test
    fun hostResultsAreTypeCheckedAndArithmeticFaultsAreLocated() {
        val wrong = run(Program("wrong", handlers = listOf(everyTick(P.eval(host("wrongType", id = "block:wrong"))))))
        wrong.world.step()
        assertEquals(FaultKind.HostFailure, wrong.instance.fault?.kind)
        val divide = run(Program("divide", handlers = listOf(everyTick(P.eval(op(BinaryOp.Divide, int(1), int(0), id = "block:divide"))))))
        divide.world.step()
        assertEquals(FaultKind.InvalidArithmetic, divide.instance.fault?.kind)
        assertEquals(
            "block:divide",
            divide.instance.fault
                ?.source
                ?.nodeId,
        )
        val index =
            run(
                Program(
                    "index",
                    handlers =
                        listOf(
                            everyTick(P.eval(Expr.At(Expr.ListOf(IntegerType, listOf(int(1)), s("list")), int(1), s("block:at")))),
                        ),
                ),
            )
        index.world.step()
        assertEquals(FaultKind.IndexOutOfRange, index.instance.fault?.kind)
    }

    @Test
    fun readsInsideASegmentSeeItsOwnBufferedWrites() {
        val program =
            Program(
                "buffer",
                state = listOf(field("seen", IntegerValue(0))),
                handlers =
                    listOf(
                        everyTick(
                            setProp(self(), "value", int(41)),
                            setState("seen", op(BinaryOp.Add, get(self(), "value"), int(1))),
                        ),
                    ),
            )
        val run = run(program, value = 0)
        run.world.step()
        assertEquals(IntegerValue(42), run.instance.state("seen"))
        assertEquals(41L, run.world.get(run.entity, run.fixture.value))
    }

    @Test
    fun fuelChargesAreDeterministicAndIncludeHostCosts() {
        val program = Program("fuel", handlers = listOf(everyTick(P.eval(host("bonus", int(2))))))
        val a = run(program)
        val b = run(program)
        repeat(3) {
            a.world.step()
            b.world.step()
        }
        // Per segment: push, call-host (1 + cost 5), pop, end = 9 fuel.
        assertEquals(27L, a.instance.fuelUsed)
        assertEquals(a.instance.fuelUsed, b.instance.fuelUsed)
    }

    @Test
    fun acknowledgedEffectsAreRemovedInOrder() {
        val program = Program("effects", handlers = listOf(everyTick(effect("analytics", int(1)), effect("analytics", int(2)))))
        val run = run(program)
        run.world.step()
        run.world.step()
        assertEquals(listOf("0.0:0:0", "0.0:0:1", "0.0:1:0", "0.0:1:1"), run.instance.pendingEffects.map { it.id })
        run.instance.acknowledgeEffects("0.0:0:1")
        assertEquals(listOf("0.0:1:0", "0.0:1:1"), run.instance.pendingEffects.map { it.id })
    }
}
