package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeferredCommandsTest {
    @Test
    fun structuralChangesCommitAfterAllSystemsInRecordedOrder() {
        val builder = RuntimeBuilder()
        val value = builder.component<Int>("test:value", AuthoringInfo("Value"))
        var observed = emptyList<Int?>()
        builder.system("test:edit") {
            WorldSystem { world, commands ->
                world.entities(value).forEach {
                    commands.destroy(it)
                    commands.set(it, value, 99) // Destroyed earlier in this batch: safely ignored.
                }
                commands.spawn { entity -> set(entity, value, 4) }
            }
        }
        builder.system("test:observe", after = setOf("test:edit")) {
            WorldSystem { world, _ -> observed = world.entities(value).map { world.get(it, value) } }
        }
        val world = builder.build().createWorld()
        val original = world.spawn()
        world.set(original, value, 1)
        world.step()
        assertEquals(listOf(1), observed)
        assertFalse(world.isAlive(original))
        assertEquals(listOf(4), world.entities(value).map { world.get(it, value) })
    }

    @Test
    fun existingValuesCanUpdateImmediatelyButDirectStructuralEditsAreRejected() {
        val builder = RuntimeBuilder()
        val value = builder.component<Int>("test:value", AuthoringInfo("Value"))
        val extra = builder.component<Int>("test:extra", AuthoringInfo("Extra"))
        builder.system("test:update") {
            WorldSystem { world, _ ->
                val entity = world.entities(value).single()
                world.set(entity, value, 5)
                assertFailsWith<IllegalStateException> { world.spawn() }
                assertFailsWith<IllegalStateException> { world.destroy(entity) }
                assertFailsWith<IllegalStateException> { world.remove(entity, value) }
                assertFailsWith<IllegalStateException> { world.set(entity, extra, 1) }
            }
        }
        val world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, value, 1)
        world.step()
        assertEquals(5, world.get(entity, value))
    }

    @Test
    fun escapedCommandsExpireAndNativeFailuresFaultTheWorldWithoutClaimingRollback() {
        val builder = RuntimeBuilder()
        val value = builder.component<Int>("test:value", AuthoringInfo("Value"))
        var escaped: WorldCommands? = null
        builder.system("test:fail") {
            WorldSystem { world, commands ->
                escaped = commands
                world.entities(value).forEach { world.set(it, value, 7) }
                commands.spawn()
                error("native extension failed")
            }
        }
        val world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, value, 1)
        assertFailsWith<IllegalStateException> { world.step() }
        assertTrue(world.isFaulted)
        assertEquals("test:fail", world.failedSystem)
        assertEquals(1, world.entityCount) // Uncommitted spawn was discarded.
        assertEquals(7, world.get(entity, value)) // Arbitrary native writes were not rolled back.
        assertFailsWith<IllegalStateException> { escaped!!.spawn() }
        assertFailsWith<IllegalStateException> { world.spawn() }
    }

    @Test
    fun deferredDetachAndReattachAreOrderedAndCommandsExpireAfterSuccessfulCommit() {
        val builder = RuntimeBuilder()
        val value = builder.component<Int>("test:value", AuthoringInfo("Value"))
        var escaped: WorldCommands? = null
        builder.system("test:replace") {
            WorldSystem { world, commands ->
                escaped = commands
                val entity = world.entities(value).single()
                commands.remove(entity, value)
                commands.set(entity, value, 12)
                assertEquals(1, world.get(entity, value))
            }
        }
        val world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, value, 1)
        world.step()
        assertEquals(12, world.get(entity, value))
        assertFailsWith<IllegalStateException> { escaped!!.remove(entity, value) }
    }

    @Test
    fun commitFailureIdentifiesItsOriginAndPreventsFurtherUpdates() {
        val builder = RuntimeBuilder()
        builder.system("test:origin") {
            WorldSystem { _, commands -> commands.spawn { error("bad initializer") } }
        }
        builder.system("test:later", after = setOf("test:origin")) { WorldSystem { _, _ -> } }
        val world = builder.build().createWorld()
        assertFailsWith<IllegalStateException> { world.step() }
        assertEquals("test:origin", world.failedSystem)
        assertEquals(1, world.entityCount) // Trusted native initialization already allocated this entity.
        assertTrue(world.isFaulted)
        assertFailsWith<IllegalStateException> { world.step() }
    }
}
