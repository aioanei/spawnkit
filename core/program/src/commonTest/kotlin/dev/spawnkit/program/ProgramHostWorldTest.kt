package dev.spawnkit.program

import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.host
import dev.spawnkit.program.P.setState
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCodecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgramHostWorldTest {
    @Test
    fun hostFunctionsCanQueryTheWorldTheProgramRunsIn() {
        val builder = RuntimeBuilder()
        val coin = builder.component("test:coin", AuthoringInfo("Coin"), StateCodecs.long)
        val host =
            ProgramHostBuilder()
                .function("coinsLeft", emptyList(), IntegerType, AuthoringInfo("Coins left")) { call ->
                    IntegerValue(
                        call.world
                            .entities(coin)
                            .size
                            .toLong(),
                    )
                }.build()
        val feature = ProgramFeature(builder, host)
        val runtime = builder.build()
        val world = runtime.createWorld()
        repeat(3) { world.set(world.spawn(), coin, 1L) }
        val program =
            Program(
                "counter",
                state = listOf(field("left", IntegerValue(0))),
                handlers = listOf(everyTick(setState("left", host("coinsLeft")))),
            )
        val prepared = (prepareProgram(program, host) as PrepareResult.Ready).program
        val runner = world.spawn()
        world.set(runner, feature.behavior, feature.instantiate(prepared))
        world.step()
        val instance = checkNotNull(world.get(runner, feature.behavior))
        assertNull(instance.fault)
        assertEquals(IntegerValue(3), instance.state("left"))
    }
}
