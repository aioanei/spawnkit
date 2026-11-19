package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScheduleTest {
    @Test
    fun orderingUsesPhasesDependenciesAndStableIdsRatherThanRegistrationOrder() {
        val events = mutableListOf<String>()
        val builder = RuntimeBuilder()
        builder.system("test:last", SystemPhase.AfterUpdate) { WorldSystem { _, _ -> events += "last" } }
        builder.system("test:b") { WorldSystem { _, _ -> events += "b" } }
        builder.system("test:a", after = setOf("test:b")) { WorldSystem { _, _ -> events += "a" } }
        builder.system("test:first", SystemPhase.BeforeUpdate) { WorldSystem { _, _ -> events += "first" } }
        val runtime = builder.build()
        assertEquals(listOf("test:first", "test:b", "test:a", "test:last"), runtime.systemOrder)
        runtime.createWorld().step()
        assertEquals(listOf("first", "b", "a", "last"), events)
    }

    @Test
    fun unrelatedSystemsHaveTheSameOrderForDifferentRegistrationOrders() {
        fun order(ids: List<String>): List<String> {
            val builder = RuntimeBuilder()
            ids.forEach { builder.system(it) { WorldSystem { _, _ -> } } }
            return builder.build().systemOrder
        }
        assertEquals(order(listOf("test:z", "test:a")), order(listOf("test:a", "test:z")))
    }

    @Test
    fun missingDependenciesCyclesAndPhaseContradictionsFailAtBuild() {
        val missing = RuntimeBuilder()
        missing.system("test:a", after = setOf("test:missing")) { WorldSystem { _, _ -> } }
        assertTrue(assertFailsWith<IllegalArgumentException> { missing.build() }.message.orEmpty().contains("test:missing"))
        val cycle = RuntimeBuilder()
        cycle.system("test:a", before = setOf("test:b")) { WorldSystem { _, _ -> } }
        cycle.system("test:b", before = setOf("test:a")) { WorldSystem { _, _ -> } }
        assertFailsWith<IllegalArgumentException> { cycle.build() }
        val phases = RuntimeBuilder()
        phases.system("test:early", SystemPhase.BeforeUpdate, after = setOf("test:late")) { WorldSystem { _, _ -> } }
        phases.system("test:late", SystemPhase.AfterUpdate) { WorldSystem { _, _ -> } }
        assertFailsWith<IllegalArgumentException> { phases.build() }
    }

    @Test
    fun duplicateSystemsAndReentrantStepsAreRejected() {
        val builder = RuntimeBuilder()
        builder.system("test:recursive") { WorldSystem { world, _ -> world.step() } }
        assertFailsWith<IllegalArgumentException> { builder.system("test:recursive") { WorldSystem { _, _ -> } } }
        val world = builder.build().createWorld()
        assertFailsWith<IllegalStateException> { world.step() }
        assertTrue(world.isFaulted)
        assertFailsWith<IllegalStateException> { world.step() }
    }
}
