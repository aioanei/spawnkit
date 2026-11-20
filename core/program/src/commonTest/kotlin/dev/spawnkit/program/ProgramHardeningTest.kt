package dev.spawnkit.program

import dev.spawnkit.program.P.assign
import dev.spawnkit.program.P.bool
import dev.spawnkit.program.P.declare
import dev.spawnkit.program.P.emit
import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.get
import dev.spawnkit.program.P.ifThen
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.loop
import dev.spawnkit.program.P.onEvent
import dev.spawnkit.program.P.op
import dev.spawnkit.program.P.s
import dev.spawnkit.program.P.self
import dev.spawnkit.program.P.setState
import dev.spawnkit.program.P.state
import dev.spawnkit.program.P.text
import dev.spawnkit.program.P.v
import dev.spawnkit.program.P.wait
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCodecs
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for review findings and documented edge semantics. */
class ProgramHardeningTest {
    private val fixture = ProgramFixture()

    private fun attach(
        program: Program,
        world: World = fixture.runtime.createWorld(),
    ): Pair<World, EntityHandle> {
        val entity = world.spawn()
        world.set(entity, fixture.value, 1L)
        world.set(entity, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(program)))
        return world to entity
    }

    private fun instance(
        world: World,
        entity: EntityHandle,
    ) = checkNotNull(world.get(entity, fixture.feature.behavior))

    private fun invalid(program: Program): List<Diagnostic> =
        assertIs<PrepareResult.Invalid>(prepareProgram(program, fixture.host)).diagnostics

    @Test
    fun listLiteralsAndStateMustMatchTheirDeclaredItemTypes() {
        val badList = ListValue(IntegerType, listOf(TextValue("a")))
        val literal = invalid(Program("bad-literal", handlers = listOf(everyTick(P.eval(Expr.Literal(badList, s("block:list")))))))
        assertEquals(listOf("block:list"), literal.map { it.source.nodeId })
        val stateful = invalid(Program("bad-state", state = listOf(field("items", badList))))
        assertEquals(listOf("field:items"), stateful.map { it.source.nodeId })
    }

    @Test
    fun objectConstantsAreRejectedEvenInsideLists() {
        val world = fixture.runtime.createWorld()
        val handle = world.spawn()
        val hidden = ListValue(EntityType, listOf(EntityValue(handle)))
        val found = invalid(Program("hidden", handlers = listOf(everyTick(P.eval(Expr.Literal(hidden, s("block:hidden")))))))
        assertEquals(listOf("block:hidden"), found.map { it.source.nodeId })
    }

    @Test
    fun textGrowthIsBoundedByTheTextLimit() {
        val program =
            Program(
                "text",
                handlers =
                    listOf(
                        everyTick(
                            declare("s", text("ab")),
                            loop(bool(true), listOf(assign("s", op(BinaryOp.Add, v("s"), v("s"), id = "block:double")))),
                        ),
                    ),
            )
        val (world, entity) = attach(program)
        world.step()
        val fault = instance(world, entity).fault
        assertEquals(FaultKind.AllocationExceeded, fault?.kind)
        assertEquals("block:double", fault?.source?.nodeId)
    }

    @Test
    fun selfAmplifyingEventsStopTheReceiverAtTheInboxLimit() {
        val program =
            Program(
                "storm",
                state = listOf(field("started", BoolValue(false))),
                handlers =
                    listOf(
                        everyTick(
                            ifThen(
                                op(BinaryOp.Equal, state("started"), bool(false)),
                                listOf(setState("started", bool(true)), emit("ping", null)),
                            ),
                        ),
                        onEvent("ping", *Array(200) { emit("ping", null) }, id = "rule:ping"),
                    ),
            )
        val (world, entity) = attach(program)
        repeat(4) { world.step() }
        val fault = instance(world, entity).fault
        assertEquals(FaultKind.MessageLimitExceeded, fault?.kind)
        assertEquals("rule:ping", fault?.source?.nodeId)
        assertFalse(world.isFaulted)
    }

    @Test
    fun fingerprintsCoverTriggersAndStateDeclarations() {
        val base = Program("p", state = listOf(field("a", IntegerValue(0))), handlers = listOf(onEvent("ping")))
        val retriggered = base.copy(handlers = listOf(onEvent("collected")))
        val extraField = base.copy(state = base.state + field("b", IntegerValue(0)))
        val newInitial = base.copy(state = listOf(field("a", IntegerValue(1))))
        val prints = listOf(base, retriggered, extraField, newInitial).map { fixture.prepare(it).fingerprint }
        assertEquals(prints.size, prints.toSet().size, "Every declaration change alters the fingerprint")
        assertNotEquals(fixture.prepare(base).fingerprint, fixture.prepare(base.copy(id = "q")).fingerprint)
    }

    @Test
    fun wronglyTypedPropertyReadersFaultInsteadOfCrashing() {
        val builder = RuntimeBuilder()
        val raw = builder.component("test:raw", AuthoringInfo("Raw"), StateCodecs.long)
        val host =
            ProgramHostBuilder()
                .property("raw", raw, IntegerType, AuthoringInfo("Raw"), { TextValue("not a number") }, { current, _ -> current })
                .build()
        val feature = ProgramFeature(builder, host)
        val program =
            Program(
                "reader",
                state = listOf(field("x", IntegerValue(0))),
                handlers = listOf(everyTick(setState("x", get(self(), "raw", id = "block:read")))),
            )
        val prepared = assertIs<PrepareResult.Ready>(prepareProgram(program, host)).program
        val world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, raw, 5L)
        world.set(entity, feature.behavior, feature.instantiate(prepared))
        world.step()
        val fault = checkNotNull(world.get(entity, feature.behavior)).fault
        assertEquals(FaultKind.HostFailure, fault?.kind)
        assertEquals("block:read", fault?.source?.nodeId)
        assertFalse(world.isFaulted)
    }

    @Test
    fun suspendedWaitsAndMessagesAreLimitedPerInstanceAndSegment() {
        val waits = Program("waits", handlers = listOf(everyTick(wait(int(1_000), id = "block:wait"))))
        val (world, entity) = attach(waits)
        repeat(fixture.host.limits.maxSuspendedThreads + 1) { world.step() }
        assertEquals(FaultKind.MessageLimitExceeded, instance(world, entity).fault?.kind)
        assertEquals("block:wait", instance(world, entity).fault?.source?.nodeId)
        assertEquals(0, instance(world, entity).suspendedCount)

        val chatty = Program("chatty", handlers = listOf(everyTick(loop(bool(true), listOf(emit("ping", null, id = "block:emit"))))))
        val (world2, entity2) = attach(chatty)
        world2.step()
        assertEquals(FaultKind.MessageLimitExceeded, instance(world2, entity2).fault?.kind)
    }

    @Test
    fun integerEdgeCasesShortCircuitsAndInvalidWaitsAreDefined() {
        val program =
            Program(
                "edges",
                state =
                    listOf(
                        field("quotient", IntegerValue(0)),
                        field("remainder", IntegerValue(1)),
                        field("skipped", BoolValue(false)),
                        field("nanEqual", BoolValue(true)),
                    ),
                handlers =
                    listOf(
                        everyTick(
                            setState("quotient", op(BinaryOp.Divide, int(Long.MIN_VALUE), int(-1))),
                            setState("remainder", op(BinaryOp.Remainder, int(Long.MIN_VALUE), int(-1))),
                            // The right operands would divide by zero if evaluated.
                            declare("and", op(BinaryOp.And, bool(false), op(BinaryOp.Equal, op(BinaryOp.Divide, int(1), int(0)), int(0)))),
                            declare("or", op(BinaryOp.Or, bool(true), op(BinaryOp.Equal, op(BinaryOp.Divide, int(1), int(0)), int(0)))),
                            setState("skipped", op(BinaryOp.And, op(BinaryOp.Equal, v("and"), bool(false)), v("or"))),
                            declare("big", Expr.Literal(DecimalValue(1e308), s("lit"))),
                            declare("inf", op(BinaryOp.Multiply, v("big"), Expr.Literal(DecimalValue(10.0), s("lit")))),
                            declare("nan", op(BinaryOp.Subtract, v("inf"), v("inf"))),
                            setState("nanEqual", op(BinaryOp.Equal, v("nan"), v("nan"))),
                        ),
                    ),
            )
        val (world, entity) = attach(program)
        world.step()
        val result = instance(world, entity)
        assertNull(result.fault)
        assertEquals(IntegerValue(Long.MIN_VALUE), result.state("quotient"))
        assertEquals(IntegerValue(0), result.state("remainder"))
        assertEquals(BoolValue(true), result.state("skipped"))
        assertEquals(BoolValue(false), result.state("nanEqual"), "Decimal equality follows IEEE-754")

        val (world2, entity2) = attach(Program("zero-wait", handlers = listOf(everyTick(wait(int(0), id = "block:wait0")))))
        world2.step()
        assertEquals(FaultKind.InvalidArithmetic, instance(world2, entity2).fault?.kind)
    }

    @Test
    fun staleObjectsFaultWithMissingProperty() {
        val world = fixture.runtime.createWorld()
        val victim = world.spawn()
        world.set(victim, fixture.value, 3L)
        val program =
            Program(
                "stale",
                state = listOf(field("seen", IntegerValue(0))),
                handlers =
                    listOf(
                        everyTick(
                            declare("found", P.query("value")),
                            wait(int(1)),
                            P.each("e", v("found"), listOf(setState("seen", get(v("e"), "value", id = "block:stale")))),
                        ),
                    ),
            )
        val brain = world.spawn()
        world.set(brain, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(program)))
        world.step()
        world.destroy(victim)
        world.step()
        val fault = instance(world, brain).fault
        assertEquals(FaultKind.MissingProperty, fault?.kind)
        assertEquals("block:stale", fault?.source?.nodeId)
        assertTrue(fault?.message?.contains("no longer exists") == true)
    }

    @Test
    fun nestedBlocksReuseSlotsWithoutClobberingLiveLocals() {
        val program =
            Program(
                "slots",
                state = listOf(field("result", IntegerValue(0))),
                handlers =
                    listOf(
                        everyTick(
                            declare("outer", int(100)),
                            ifThen(bool(true), listOf(declare("inner", int(1)), assign("outer", op(BinaryOp.Add, v("outer"), v("inner"))))),
                            ifThen(
                                bool(true),
                                listOf(declare("other", int(10)), assign("outer", op(BinaryOp.Add, v("outer"), v("other")))),
                            ),
                            P.each(
                                "n",
                                Expr.ListOf(IntegerType, listOf(int(1), int(2)), s("list")),
                                listOf(
                                    declare("tmp", op(BinaryOp.Multiply, v("n"), int(1000))),
                                    assign("outer", op(BinaryOp.Add, v("outer"), v("tmp"))),
                                ),
                            ),
                            declare("after", int(5)),
                            setState("result", op(BinaryOp.Add, v("outer"), v("after"))),
                        ),
                    ),
            )
        val (world, entity) = attach(program)
        world.step()
        assertEquals(IntegerValue(100 + 1 + 10 + 3000 + 5), instance(world, entity).state("result"))
    }

    @Test
    fun excessiveNestingIsADiagnosticNotAStackOverflow() {
        var expr: Expr = int(0)
        repeat(5_000) { expr = op(BinaryOp.Add, expr, int(1)) }
        val found = invalid(Program("deep", handlers = listOf(everyTick(P.eval(expr)))))
        assertTrue(found.any { it.message.contains("nested too deeply") })
    }
}
