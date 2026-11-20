package dev.spawnkit.program

import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.host
import dev.spawnkit.program.P.ifThen
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.op
import dev.spawnkit.program.P.setState
import dev.spawnkit.program.P.state
import dev.spawnkit.program.P.text
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProgramInputTest {
    private class Fixture {
        val builder = RuntimeBuilder()
        val jump = builder.action("game:jump", AuthoringInfo("Jump"))
        val moveX = builder.axis("game:move-x", AuthoringInfo("Move"))
        val host = ProgramHostBuilder().inputs(listOf(jump), listOf(moveX)).build()
        val feature = ProgramFeature(builder, host)
        val runtime = builder.build()

        // Counts presses, accumulates the axis and counts held ticks: all through rules, no native gameplay code.
        val program =
            Program(
                "controls",
                state = listOf(field("jumps", IntegerValue(0)), field("held", IntegerValue(0)), field("x", DecimalValue(0.0))),
                handlers =
                    listOf(
                        everyTick(
                            ifThen(
                                host("wasPressed", text("game:jump")),
                                listOf(setState("jumps", op(BinaryOp.Add, state("jumps"), int(1)))),
                            ),
                            ifThen(host("isDown", text("game:jump")), listOf(setState("held", op(BinaryOp.Add, state("held"), int(1))))),
                            setState("x", op(BinaryOp.Add, state("x"), host("axis", text("game:move-x")))),
                        ),
                    ),
            )

        fun world(program: Program = this.program): World {
            val prepared = (prepareProgram(program, host) as PrepareResult.Ready).program
            return runtime.createWorld().also { it.set(it.spawn(), feature.behavior, feature.instantiate(prepared)) }
        }

        fun instance(world: World) = checkNotNull(world.get(world.entities().single(), feature.behavior))
    }

    @Test
    fun rulesReadEdgesHeldStateAndAxesFromRecordedFrames() {
        val fixture = Fixture()
        val recording =
            listOf(
                InputFrame(down = setOf(fixture.jump), axes = mapOf(fixture.moveX to 1.0)),
                InputFrame(down = setOf(fixture.jump), axes = mapOf(fixture.moveX to 0.5)),
                InputFrame(),
                InputFrame(down = setOf(fixture.jump), axes = mapOf(fixture.moveX to -0.25)),
            )
        val live = fixture.world()
        recording.forEach {
            live.submitInput(it)
            live.step()
        }
        val instance = fixture.instance(live)
        assertNull(instance.fault)
        assertEquals(IntegerValue(2), instance.state("jumps"))
        assertEquals(IntegerValue(3), instance.state("held"))
        assertEquals(DecimalValue(1.25), instance.state("x"))
        val replay = fixture.world()
        recording.forEach {
            replay.submitInput(it)
            replay.step()
        }
        assertEquals(live.stateHash(), replay.stateHash())
    }

    @Test
    fun unknownInputNamesFaultTheRule() {
        val fixture = Fixture()
        val world =
            fixture.world(
                Program("typo", handlers = listOf(everyTick(P.eval(host("isDown", text("game:jmup"), id = "block:typo"))))),
            )
        world.step()
        val fault = fixture.instance(world).fault
        assertEquals(FaultKind.HostFailure, fault?.kind)
        assertEquals("block:typo", fault?.source?.nodeId)
    }

    @Test
    fun releasesAreVisibleAndForeignInputsFaultTheRuleNotTheWorld() {
        val fixture = Fixture()
        val released =
            Program(
                "release",
                state = listOf(field("releases", IntegerValue(0))),
                handlers =
                    listOf(
                        everyTick(
                            ifThen(
                                host("wasReleased", text("game:jump")),
                                listOf(setState("releases", op(BinaryOp.Add, state("releases"), int(1)))),
                            ),
                        ),
                    ),
            )
        val world = fixture.world(released)
        world.submitInput(InputFrame(down = setOf(fixture.jump)))
        world.step()
        world.submitInput(InputFrame())
        world.step()
        assertEquals(IntegerValue(1), fixture.instance(world).state("releases"))

        // A host built with another runtime's actions runs inside this runtime's world.
        val other = Fixture()
        val mixedHost = ProgramHostBuilder().inputs(listOf(other.jump)).build()
        val mixedBuilder = RuntimeBuilder()
        val mixedFeature = ProgramFeature(mixedBuilder, mixedHost)
        val mixedWorld = mixedBuilder.build().createWorld()
        val prepared =
            (
                prepareProgram(
                    Program("mixed", handlers = listOf(everyTick(P.eval(host("isDown", text("game:jump")))))),
                    mixedHost,
                ) as PrepareResult.Ready
            ).program
        val entity = mixedWorld.spawn()
        mixedWorld.set(entity, mixedFeature.behavior, mixedFeature.instantiate(prepared))
        mixedWorld.step()
        assertEquals(FaultKind.HostFailure, checkNotNull(mixedWorld.get(entity, mixedFeature.behavior)).fault?.kind)
        assertFalse(mixedWorld.isFaulted)
    }

    @Test
    fun inputRegistrationIsAtomicAndRejectsDuplicates() {
        val fixture = Fixture()
        val clash =
            ProgramHostBuilder().function("axis", emptyList(), DecimalType, AuthoringInfo("Mine")) { DecimalValue(0.0) }
        assertFailsWith<IllegalArgumentException> { clash.inputs(listOf(fixture.jump), listOf(fixture.moveX)) }
        assertEquals(listOf("axis"), clash.build().functionCatalog.map { it.name })
        assertFailsWith<IllegalArgumentException> { ProgramHostBuilder().inputs(listOf(fixture.jump, Fixture().jump)) }
    }
}
