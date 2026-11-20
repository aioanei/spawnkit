package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentStorageTest {
    @Test
    fun columnAttachReplaceRemoveAndSwapKeepEveryEntryAddressable() {
        val column = ComponentColumn()
        for (slot in listOf(40, 3, 17, 0, 25)) column.put(slot, "v$slot")
        assertEquals(5, column.size)
        column.put(17, "replaced")
        assertEquals(5, column.size)
        assertEquals("replaced", column.get(17))
        // Removing the first dense entry swaps the last one (slot 25) into its place.
        assertEquals("v40", column.remove(40))
        assertNull(column.remove(40))
        assertNull(column.remove(999))
        assertFalse(column.contains(40))
        assertEquals(listOf(0, 3, 17, 25), column.sortedEntries().map { it.first })
        assertEquals(listOf("v0", "v3", "replaced", "v25"), column.sortedEntries().map { it.second })
        for (slot in listOf(0, 3, 17, 25)) assertTrue(column.contains(slot))
    }

    @Test
    fun columnGrowsBeyondInitialCapacityAndEmptiesCompletely() {
        val column = ComponentColumn()
        repeat(1_000) { column.put(999 - it, it) }
        assertEquals(1_000, column.size)
        assertEquals((0 until 1_000).toList(), column.sortedEntries().map { it.first })
        repeat(1_000) { assertEquals(999 - it, column.remove(it)) }
        assertEquals(0, column.size)
        assertEquals(emptyList(), column.sortedEntries())
    }

    @Test
    fun sparseAndDenseQueriesBothReturnAscendingSlotOrder() {
        val builder = RuntimeBuilder()
        val dense = builder.component<Int>("test:dense", AuthoringInfo("Dense"))
        val rare = builder.component<Int>("test:rare", AuthoringInfo("Rare"))
        val world = builder.build().createWorld()
        val entities = List(200) { world.spawn() }
        entities.forEach { world.set(it, dense, it.index) }
        // Attach the rare component in descending slot order so dense storage order is reversed.
        val rareSlots = listOf(190, 120, 64, 7)
        rareSlots.forEach { world.set(entities[it], rare, it) }
        assertEquals(rareSlots.sorted(), world.entities(rare).map { it.index })
        assertEquals(rareSlots.sorted(), world.entities(dense, rare).map { it.index })
        assertEquals(rareSlots.sorted(), world.entities(rare, dense).map { it.index })
        world.remove(entities[64], rare)
        world.destroy(entities[120])
        assertEquals(listOf(7, 190), world.entities(dense, rare).map { it.index })
        val all = world.entities(dense)
        assertEquals(199, all.size)
        assertEquals(all.map { it.index }.sorted(), all.map { it.index })
    }

    @Test
    fun queriesForUnusedOrEmptiedComponentsAreEmpty() {
        val builder = RuntimeBuilder()
        val used = builder.component<Int>("test:used", AuthoringInfo("Used"))
        val unused = builder.component<Int>("test:unused", AuthoringInfo("Unused"))
        val world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, used, 1)
        assertEquals(emptyList(), world.entities(unused))
        assertEquals(emptyList(), world.entities(used, unused))
        world.remove(entity, used)
        assertEquals(emptyList(), world.entities(used))
        assertEquals(listOf(entity), world.entities())
    }

    @Test
    fun liveHandlesAreReusedAcrossQueriesAndReplacedAfterSlotReuse() {
        val builder = RuntimeBuilder()
        val tag = builder.component<Int>("test:tag", AuthoringInfo("Tag"))
        val world = builder.build().createWorld()
        val first = world.spawn()
        world.set(first, tag, 1)
        assertSame(world.entities(tag).single(), world.entities().single())
        world.destroy(first)
        val second = world.spawn()
        assertEquals(first.index, second.index)
        assertFalse(world.isAlive(first))
        assertEquals(second, world.entities().single())
        assertEquals(first.generation + 1, world.entities().single().generation)
    }
}
