package dev.spawnkit.runtime

/**
 * Sparse-set storage for one component type in one world. [sparse] maps an entity slot to its dense position plus
 * one (zero means absent); [denseSlots] and [values] are packed. Removal swaps the last entry into the hole, so dense
 * order is arbitrary: every observable iteration goes through [World.entities] or [sortedEntries], which restore
 * ascending slot order. Selected by the R1 storage comparison (issue #26); see docs/runtime-storage.md.
 */
internal class ComponentColumn(
    capacity: Int = INITIAL_CAPACITY,
    slotRange: Int = 0,
) {
    private var sparse = IntArray(slotRange)
    private var denseSlots = IntArray(maxOf(capacity, INITIAL_CAPACITY))
    private var values = arrayOfNulls<Any>(maxOf(capacity, INITIAL_CAPACITY))

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
        val result = ArrayList<Pair<Int, Any>>(size)
        forEachInSlotOrder { slot, value -> result += slot to value }
        return result
    }

    /** Visits all entries in ascending slot order without building an intermediate list. */
    inline fun forEachInSlotOrder(action: (slot: Int, value: Any) -> Unit) {
        if (size.toLong() * SORT_COST_FACTOR >= sparseRange) {
            // Dense relative to the slot range: walking the sparse index is already in slot order.
            for (slot in 0 until sparseRange) {
                val value = get(slot)
                if (value != null) action(slot, value)
            }
            return
        }
        val slots = IntArray(size) { slotAt(it) }
        slots.sort()
        for (slot in slots) action(slot, checkNotNull(get(slot)))
    }

    /** Current extent of the slot index; slots at or beyond it are absent. */
    val sparseRange: Int get() = sparse.size

    internal companion object {
        private const val INITIAL_CAPACITY = 8

        /**
         * Ordered iteration either sorts the k dense slots (about k·log2(k) work) or walks the whole slot range.
         * Walk the range once k·16 reaches it; queries in [World.entities] use the same rule.
         */
        const val SORT_COST_FACTOR = 16L
    }
}
