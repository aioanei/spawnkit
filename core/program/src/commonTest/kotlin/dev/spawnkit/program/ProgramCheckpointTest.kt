package dev.spawnkit.program

import dev.spawnkit.program.P.assign
import dev.spawnkit.program.P.call
import dev.spawnkit.program.P.declare
import dev.spawnkit.program.P.each
import dev.spawnkit.program.P.effect
import dev.spawnkit.program.P.emit
import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.get
import dev.spawnkit.program.P.host
import dev.spawnkit.program.P.ifThen
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.onEvent
import dev.spawnkit.program.P.op
import dev.spawnkit.program.P.query
import dev.spawnkit.program.P.ret
import dev.spawnkit.program.P.s
import dev.spawnkit.program.P.self
import dev.spawnkit.program.P.setProp
import dev.spawnkit.program.P.setState
import dev.spawnkit.program.P.state
import dev.spawnkit.program.P.v
import dev.spawnkit.program.P.wait
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R1 acceptance fixture: a user function iterates queried objects, computes with state and a list, emits a custom
 * event, waits on a timer and resumes. Checkpoints taken while waits are suspended restore and replay exactly.
 */
class ProgramCheckpointTest {
    private val collector =
        Program(
            "collector",
            state = listOf(field("total", IntegerValue(0)), field("rounds", IntegerValue(0))),
            functions =
                listOf(
                    FunctionDef(
                        "weight",
                        listOf(Parameter("e", EntityType)),
                        IntegerType,
                        listOf(
                            declare("w", get(v("e"), "value")),
                            ifThen(op(BinaryOp.Greater, v("w"), int(10)), listOf(ret(op(BinaryOp.Multiply, v("w"), int(2))))),
                            ret(v("w")),
                        ),
                        s("fn:weight"),
                    ),
                ),
            handlers =
                listOf(
                    everyTick(
                        declare("sum", int(0)),
                        declare("picked", Expr.ListOf(EntityType, emptyList(), s("block:picked"))),
                        each(
                            "e",
                            query("value"),
                            listOf(
                                assign("sum", op(BinaryOp.Add, v("sum"), call("weight", v("e")))),
                                ifThen(
                                    op(BinaryOp.Greater, get(v("e"), "value"), int(5)),
                                    listOf(assign("picked", Expr.Append(v("picked"), v("e"), s("block:pick")))),
                                ),
                            ),
                        ),
                        setState("total", op(BinaryOp.Add, state("total"), v("sum"))),
                        emit("collected", v("sum")),
                        wait(int(3)),
                        each("e", v("picked"), listOf(setProp(v("e"), "value", op(BinaryOp.Subtract, get(v("e"), "value"), int(1))))),
                        setState("rounds", op(BinaryOp.Add, state("rounds"), int(1))),
                        effect("analytics", state("rounds")),
                    ),
                ),
        )

    private val scorer =
        Program(
            "scorer",
            handlers =
                listOf(
                    onEvent(
                        "collected",
                        setProp(
                            self(),
                            "score",
                            op(BinaryOp.Add, get(self(), "score"), host("bonus", op(BinaryOp.Remainder, v("payload"), int(7)))),
                        ),
                    ),
                ),
        )

    private class Scene(
        val fixture: ProgramFixture,
        val world: World,
    ) {
        val collector get() = checkNotNull(world.get(world.entities(fixture.feature.behavior).first(), fixture.feature.behavior))
        val scorerEntity get() = world.entities(fixture.feature.behavior)[1]
    }

    private fun scene(): Scene {
        val fixture = ProgramFixture()
        val world = fixture.runtime.createWorld()
        val brain = world.spawn()
        world.set(brain, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(collector)))
        val counter = world.spawn()
        world.set(counter, fixture.score, 0L)
        world.set(counter, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(scorer)))
        for (value in listOf(3L, 8L, 12L, 20L, 6L)) world.set(world.spawn(), fixture.value, value)
        return Scene(fixture, world)
    }

    @Test
    fun fixtureComputesEmitsWaitsAndResumes() {
        val scene = scene()
        repeat(4) { scene.world.step() }
        val collector = scene.collector
        assertNull(collector.fault)
        // Ticks 1-3 sum 3 + 8 + 24 + 40 + 6 = 81. Tick 4 first resumes the tick-1 wait (decrementing 8, 12, 20, 6),
        // then collects 3 + 7 + 22 + 38 + 5 = 75: total = 3 * 81 + 75.
        assertEquals(IntegerValue(318), collector.state("total"))
        assertEquals(IntegerValue(1), collector.state("rounds"))
        assertEquals(3, collector.suspendedCount)
        assertEquals(
            listOf(3L, 7L, 11L, 19L, 5L),
            scene.world.entities(scene.fixture.value).map { scene.world.get(it, scene.fixture.value) },
        )
        // Scorer received three collected events (ticks 2–4): bonus(81 % 7 = 4) * 3 events = 12 each → 36.
        assertEquals(36L, scene.world.get(scene.scorerEntity, scene.fixture.score))
        assertEquals(listOf("0.0:3:0"), collector.pendingEffects.map { it.id })
    }

    @Test
    fun checkpointsBeforeAndAfterWaitsReplayExactlyIncludingEffectIds() {
        for (captureTick in listOf(1, 2, 4, 5)) {
            val uninterrupted = scene()
            repeat(captureTick) { uninterrupted.world.step() }
            val snapshot = uninterrupted.world.snapshot()
            repeat(TICKS - captureTick) { uninterrupted.world.step() }

            val restored = uninterrupted.fixture.runtime.restore(snapshot)
            repeat(TICKS - captureTick) { restored.step() }
            val resumed = Scene(uninterrupted.fixture, restored)
            assertEquals(uninterrupted.world.stateHash(), restored.stateHash(), "capture at tick $captureTick")
            assertEquals(uninterrupted.collector.pendingEffects, resumed.collector.pendingEffects)
            assertEquals(uninterrupted.collector.fuelUsed, resumed.collector.fuelUsed)
        }
    }

    @Test
    fun fixtureStateMatchesTheCrossTargetGolden() {
        val scene = scene()
        repeat(TICKS) { scene.world.step() }
        assertTrue(scene.collector.suspendedCount > 0)
        assertEquals(GOLDEN_FUEL, scene.collector.fuelUsed)
        assertEquals(GOLDEN_HASH, scene.world.stateHash())
    }

    @Test
    fun restoreRequiresTheSameRegisteredProgram() {
        val scene = scene()
        scene.world.step()
        val snapshot = scene.world.snapshot()
        assertFailsWith<IllegalArgumentException> { scene.fixture.feature.lookup("unknown", 0L) }
        val prepared = scene.collector.program
        assertFailsWith<IllegalArgumentException> { scene.fixture.feature.lookup("collector", prepared.fingerprint + 1) }
        assertEquals(prepared, scene.fixture.feature.lookup("collector", prepared.fingerprint))
        assertFailsWith<IllegalArgumentException> {
            scene.fixture.feature.register(scene.fixture.prepare(collector.copy(handlers = emptyList())))
        }
        assertTrue(snapshot.entityCount > 0)
    }

    /** Recorded on JVM; every target must reproduce the same fuel count and state hash. */
    private companion object {
        const val TICKS = 12
        const val GOLDEN_FUEL = 3044L
        const val GOLDEN_HASH = 6411853139446933446L
    }
}
