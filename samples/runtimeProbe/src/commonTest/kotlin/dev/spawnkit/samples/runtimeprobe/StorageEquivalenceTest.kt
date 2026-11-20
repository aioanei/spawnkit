package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage-implementation equivalence fixture. The goldens were recorded on JVM with the original map-based
 * component store before any storage change; every store and every target must reproduce them unchanged.
 */
class StorageEquivalenceTest {
    private class Fixture(
        val scenario: StorageScenario,
        val runtime: Runtime,
        val world: World,
    )

    private fun fixture(): Fixture {
        val builder = RuntimeBuilder()
        val scenario = StorageScenario(builder)
        val runtime = builder.build()
        val world = runtime.createWorld()
        scenario.populate(world, POPULATION)
        return Fixture(scenario, runtime, world)
    }

    @Test
    fun churnScenarioMatchesTheBaselineStoreGolden() {
        val fixture = fixture()
        val world = fixture.world
        repeat(TICKS) { world.step() }
        assertTrue(world.entities(fixture.scenario.link).isNotEmpty(), "Scenario must exercise entity references")
        assertEquals(GOLDEN_ENTITY_COUNT, world.entityCount)
        assertEquals(GOLDEN_HASH, world.stateHash())
    }

    @Test
    fun queriesStayInAscendingSlotOrderAfterChurn() {
        val fixture = fixture()
        val world = fixture.world
        val scenario = fixture.scenario
        repeat(TICKS) { world.step() }
        val queries =
            listOf(
                world.entities(),
                world.entities(scenario.position),
                world.entities(scenario.marker),
                world.entities(scenario.position, scenario.velocity),
                world.entities(scenario.marker, scenario.link, scenario.position),
            )
        for (query in queries) {
            assertEquals(query.map { it.index }.sorted(), query.map { it.index })
            assertEquals(query.size, query.toSet().size)
            assertTrue(query.all(world::isAlive))
        }
        val both = world.entities(scenario.position, scenario.velocity).toSet()
        val expected = world.entities(scenario.velocity).filter { world.get(it, scenario.position) != null }.toSet()
        assertEquals(expected, both)
    }

    @Test
    fun midRunRestoreReplaysTheChurnScenarioExactly() {
        val fixture = fixture()
        val world = fixture.world
        repeat(TICKS / 2) { world.step() }
        val snapshot = world.snapshot()
        repeat(TICKS - TICKS / 2) { world.step() }
        val restored = fixture.runtime.restore(snapshot)
        repeat(TICKS - TICKS / 2) { restored.step() }
        assertEquals(world.entityCount, restored.entityCount)
        assertEquals(world.stateHash(), restored.stateHash())
        assertEquals(GOLDEN_HASH, restored.stateHash())
    }

    @Test
    fun destroyedSlotsAreReusedLastFreedFirstWithFreshGenerations() {
        val builder = RuntimeBuilder()
        val scenario = StorageScenario(builder, churnPerTick = 0)
        val world = builder.build().createWorld()
        scenario.populate(world, 5)
        val all = world.entities()
        world.destroy(all[1])
        world.destroy(all[3])
        val reused = world.spawn()
        assertEquals(3, reused.index)
        assertEquals(all[3].generation + 1, reused.generation)
        assertNull(world.get(reused, scenario.position))
        assertEquals(listOf(0, 2, 3, 4), world.entities().map { it.index })
        assertEquals(listOf(0, 2, 4), world.entities(scenario.position).map { it.index })
    }

    private companion object {
        const val POPULATION = 300
        const val TICKS = 120
        const val GOLDEN_ENTITY_COUNT = 298
        const val GOLDEN_HASH: Long = -1536454527644900448L
    }
}
