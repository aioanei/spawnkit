package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldLifecycleTest {
    @Test
    fun emptyWorldStepsWithoutDocumentOrPlayer() {
        val world = RuntimeBuilder().build().createWorld()
        assertEquals(0, world.entityCount)
        assertEquals(emptyList(), world.entities())
        world.step()
        assertEquals(1L, world.tick)
    }

    @Test
    fun customComponentsAttachReplaceAndDetach() {
        val builder = RuntimeBuilder()
        val score = builder.component<Int>("test:score", AuthoringInfo("Score", "Points earned"))
        val world = builder.build().createWorld()
        val entity = world.spawn()
        assertNull(world.get(entity, score))
        world.set(entity, score, 3)
        assertEquals(3, world.get(entity, score))
        assertEquals(listOf(entity), world.entities(score))
        world.set(entity, score, 7)
        assertEquals(7, world.remove(entity, score))
        assertEquals(emptyList(), world.entities(score))
        assertEquals("Score", score.authoring.name)
    }

    @Test
    fun destructionInvalidatesHandlesAndClearsComponents() {
        val builder = RuntimeBuilder()
        val value = builder.component<Int>("test:value", AuthoringInfo("Value"))
        val world = builder.build().createWorld()
        val old = world.spawn()
        world.set(old, value, 8)
        world.destroy(old)
        val replacement = world.spawn()
        assertEquals(old.index, replacement.index)
        assertNotEquals(old, replacement)
        assertFalse(world.isAlive(old))
        assertTrue(world.isAlive(replacement))
        assertNull(world.get(replacement, value))
        assertFailsWith<IllegalArgumentException> { world.get(old, value) }
        assertFailsWith<IllegalArgumentException> { world.destroy(old) }
        assertEquals(1, world.entityCount)
    }

    @Test
    fun worldAndComponentOwnershipAreChecked() {
        val builder = RuntimeBuilder()
        val value = builder.component<Int>("test:value", AuthoringInfo("Value"))
        val runtime = builder.build()
        val first = runtime.createWorld()
        val second = runtime.createWorld()
        val entity = first.spawn()
        second.spawn()
        assertFalse(second.isAlive(entity))
        assertFailsWith<IllegalArgumentException> { second.set(entity, value, 1) }
        val other = RuntimeBuilder().component<String>("test:value", AuthoringInfo("Other value"))
        assertFailsWith<IllegalArgumentException> { first.set(entity, other, "wrong registry") }
        assertFailsWith<IllegalArgumentException> { first.entities(other) }
    }

    @Test
    fun queriesIntersectComponentsInStableSlotOrder() {
        val builder = RuntimeBuilder()
        val a = builder.component<Int>("test:a", AuthoringInfo("A"))
        val b = builder.component<Int>("test:b", AuthoringInfo("B"))
        val world = builder.build().createWorld()
        val first = world.spawn()
        val second = world.spawn()
        val third = world.spawn()
        listOf(first, second, third).forEach { world.set(it, a, 1) }
        world.set(first, b, 2)
        world.set(third, b, 2)
        assertEquals(listOf(first, third), world.entities(a, b))
        world.destroy(second)
        val replacement = world.spawn()
        world.set(replacement, a, 1)
        assertEquals(listOf(first, replacement, third), world.entities(a))
        assertEquals(3, world.entityCount)
    }

    @Test
    fun invalidAndDuplicateIdsAndPostBuildRegistrationAreRejected() {
        val builder = RuntimeBuilder()
        assertFailsWith<IllegalArgumentException> { builder.component<Int>("Score", AuthoringInfo("Score")) }
        assertFailsWith<IllegalArgumentException> { AuthoringInfo(" ") }
        builder.component<Int>("test:value", AuthoringInfo("Value"))
        assertFailsWith<IllegalArgumentException> { builder.component<String>("test:value", AuthoringInfo("Duplicate")) }
        builder.build()
        assertFailsWith<IllegalStateException> { builder.component<Int>("test:later", AuthoringInfo("Later")) }
        assertFailsWith<IllegalStateException> { builder.build() }
    }
}
