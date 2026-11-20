package dev.spawnkit.runtime

/**
 * Sparse-set storage for one component type in one world. [sparse] maps an entity slot to its dense position plus
 * one (zero means absent); [denseSlots] and [values] are packed. Removal swaps the last entry into the hole, so dense
 * order is arbitrary: every observable iteration goes through [World.entities] or [sortedEntries], which restore
 * ascending slot order. Selected by the R1 storage comparison (issue #26); see docs/runtime-storage.md.
 */
internal class ComponentColumn {
    private var sparse = IntArray(0)
    private var denseSlots = IntArray(INITIAL_CAPACITY)
    private var values = arrayOfNulls<Any>(INITIAL_CAPACITY)

    /** Number of entities that have this component. */
    var size: Int = 0
        private set

    fun contains(slot: Int): Boolean = slot < sparse.size && sparse[slot] != 0

    fun get(slot: Int): Any? = if (slot < sparse.size && sparse[slot] != 0) values[sparse[slot] - 1] else null

    /** Slot of the dense entry [position], in arbitrary order. */
    fun slotAt(position: Int): Int = denseSlots[position]

    /** Attaches or replaces the value for [slot]. */
    fun put(
        slot: Int,
        value: Any,
    ) {
        if (slot >= sparse.size) sparse = sparse.copyOf(maxOf(slot + 1, sparse.size * 2, INITIAL_CAPACITY))
        val existing = sparse[slot]
        if (existing != 0) {
            values[existing - 1] = value
            return
        }
        if (size == denseSlots.size) {
            denseSlots = denseSlots.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        denseSlots[size] = slot
        values[size] = value
        size++
        sparse[slot] = size
    }

    /** Detaches the value for [slot] and returns it, or `null` when absent. */
    fun remove(slot: Int): Any? {
        if (slot >= sparse.size || sparse[slot] == 0) return null
        val position = sparse[slot] - 1
        val removed = values[position]
        val last = size - 1
        if (position != last) {
            val movedSlot = denseSlots[last]
            denseSlots[position] = movedSlot
            values[position] = values[last]
            sparse[movedSlot] = position + 1
        }
        values[last] = null
        sparse[slot] = 0
        size = last
        return removed
    }

    /** All entries in ascending slot order, for canonical checkpoint capture. */
    fun sortedEntries(): List<Pair<Int, Any>> {
        val slots = denseSlots.copyOf(size)
        slots.sort()
        return slots.map { it to checkNotNull(get(it)) }
    }

    private companion object {
        const val INITIAL_CAPACITY = 8
    }
}
