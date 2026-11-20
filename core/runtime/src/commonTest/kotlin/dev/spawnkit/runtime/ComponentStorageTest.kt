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
    fun orderedEntriesUseSortingForSparseColumnsAndSlotWalksForDenseOnes() {
        val sparse = ComponentColumn()
        sparse.put(5_000, "far")
        sparse.put(10, "near")
        sparse.put(2_500, "middle")
        assertEquals(listOf(10, 2_500, 5_000), sparse.sortedEntries().map { it.first })
        assertEquals(listOf("near", "middle", "far"), sparse.sortedEntries().map { it.second })
        val presized = ComponentColumn(capacity = 3, slotRange = 4)
        listOf(3, 0, 2).forEach { presized.put(it, it * 10) }
        assertEquals(listOf(0 to 0, 2 to 20, 3 to 30), presized.sortedEntries())
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

    /** Randomized check of both query strategies against a brute-force filter at densities either side of 1/16. */
    @Test
    fun queriesMatchABruteForceReferenceUnderRandomChurn() {
        val builder = RuntimeBuilder()
        val common = builder.component<Int>("test:common", AuthoringInfo("Common"))
        val rare = builder.component<Int>("test:rare", AuthoringInfo("Rare"))
        val veryRare = builder.component<Int>("test:very-rare", AuthoringInfo("Very rare"))
        val world = builder.build().createWorld()
        var seed = 12345L

        fun next(bound: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) % bound).toInt()
        }
        val types = listOf(common, rare, veryRare)
        val queries = listOf(listOf(rare), listOf(veryRare), listOf(common, rare), listOf(veryRare, common), listOf(rare, veryRare))
        repeat(3_000) { step ->
            val live = world.entities()
            when (next(10)) {
                0, 1, 2 -> {
                    world.spawn().also { if (next(2) == 0) world.set(it, common, step) }
                }

                3 -> {
                    if (live.isNotEmpty()) world.destroy(live[next(live.size)])
                }

                4 -> {
                    if (live.isNotEmpty()) world.set(live[next(live.size)], rare, step)
                }

                5 -> {
                    if (live.isNotEmpty() && next(5) == 0) world.set(live[next(live.size)], veryRare, step)
                }

                6 -> {
                    if (live.isNotEmpty()) world.remove(live[next(live.size)], types[next(3)])
                }

                else -> {
                    if (live.isNotEmpty()) world.set(live[next(live.size)], common, step)
                }
            }
            if (step % 50 == 0) {
                val all = world.entities()
                for (query in queries) {
                    val expected = all.filter { entity -> query.all { world.get(entity, it) != null } }
                    assertEquals(expected, world.entities(*query.toTypedArray()), "step $step, query ${query.map { it.id }}")
                }
            }
        }
        // Both strategies must actually have been exercised by the final state.
        val slots = world.entities().last().index + 1
        assertTrue(world.entities(veryRare).size * 16 < slots)
        assertTrue(world.entities(common).size * 16 >= slots)
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
