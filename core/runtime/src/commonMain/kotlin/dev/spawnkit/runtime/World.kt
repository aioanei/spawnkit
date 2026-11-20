package dev.spawnkit.runtime

/** The duration in seconds of one runtime simulation tick. Frame timing is supplied by the host. */
public const val FIXED_DELTA_SECONDS: Double = 1.0 / 60.0

/**
 * Genre-independent mutable runtime state. Queries are ordered by slot, never by hash-map iteration.
 * Structural changes are immediate outside a tick and deferred through [WorldCommands] during a tick.
 * Each world is confined to one caller thread; neither mutable components nor native systems are sandboxed.
 *
 * Checkpoints require explicit native state contracts; persistence, physics and portable program execution are separate.
 * Native systems are trusted: a failure faults the world and discards pending commands, but does not roll back
 * component writes or structural commands already committed. Inspect a faulted world; create a fresh one to run again.
 */
public class World internal constructor(
    private val registry: Any,
    private val registeredComponents: List<ComponentType<*>>,
    private val systems: List<Pair<String, WorldSystem>>,
) {
    private val owner = Any()
    private val slots = mutableListOf<Slot>()
    private val freeSlots = mutableListOf<Int>()
    private val columns = arrayOfNulls<ComponentColumn>(registeredComponents.size)
    private var stage = Stage.Idle
    private var currentSystem = "runtime:commands"

    /** Number of live entities. */
    public var entityCount: Int = 0
        private set

    /** Number of attempted fixed ticks, including a tick that faults. */
    public var tick: Long = 0L
        private set

    /** Whether native execution/command commit failed and further mutation or stepping is prohibited. */
    public var isFaulted: Boolean = false
        private set

    /** Registered ID responsible for a failed update/command, or `null` when this world has not faulted. */
    public var failedSystem: String? = null
        private set

    internal val checkpointIdentity: Any get() = owner

    /** Creates an empty entity, reusing free slots in deterministic last-freed-first order. */
    public fun spawn(): EntityHandle {
        requireStructuralEdit()
        val index = if (freeSlots.isEmpty()) slots.size.also { slots += Slot() } else freeSlots.removeAt(freeSlots.lastIndex)
        slots[index].alive = true
        entityCount++
        return handle(index)
    }

    /** Whether [entity] still refers to a live allocation owned by this world. */
    public fun isAlive(entity: EntityHandle): Boolean =
        entity.owner === owner && slots.getOrNull(entity.index)?.let { it.alive && it.generation == entity.generation } == true

    /** Removes an entity and its components; stale or foreign handles are rejected. */
    public fun destroy(entity: EntityHandle) {
        requireStructuralEdit()
        requireEntity(entity)
        val slot = slots[entity.index]
        check(slot.generation < Long.MAX_VALUE) { "Entity generation exhausted" }
        for (column in columns) column?.remove(entity.index)
        slot.alive = false
        slot.generation++
        slot.handle = null
        freeSlots += entity.index
        entityCount--
    }

    /**
     * Attaches or replaces a component. During system updates only replacement of an existing value is immediate;
     * attaching a new component requires [WorldCommands.set]. Values are owned by the caller; use fresh mutable
     * component instances per entity/world rather than sharing them accidentally.
     */
    public fun <T : Any> set(
        entity: EntityHandle,
        component: ComponentType<T>,
        value: T,
    ) {
        check(!isFaulted && stage != Stage.Capturing && stage != Stage.Restoring) { "World is faulted or checkpointing" }
        requireEntity(entity)
        requireComponent(component)
        val column = columns[component.ordinal]
        if (column?.contains(entity.index) != true) requireStructuralEdit()
        (column ?: ComponentColumn().also { columns[component.ordinal] = it }).put(entity.index, value)
    }

    /** Reads a component, or `null` if absent. Stale/foreign handles and foreign component registrations fail. */
    @Suppress("UNCHECKED_CAST") // Registry identity and the typed setter constrain every store entry to T.
    public fun <T : Any> get(entity: EntityHandle, component: ComponentType<T>): T? {
        requireEntity(entity)
        requireComponent(component)
        return columns[component.ordinal]?.get(entity.index) as T?
    }

    /** Detaches a component outside a system update and returns its previous value, if present. */
    @Suppress("UNCHECKED_CAST") // Same registration/type boundary as get/set.
    public fun <T : Any> remove(entity: EntityHandle, component: ComponentType<T>): T? {
        requireStructuralEdit()
        requireEntity(entity)
        requireComponent(component)
        return columns[component.ordinal]?.remove(entity.index) as T?
    }

    /**
     * Captures live handles having all [components], in ascending slot order; no types means every entity.
     * The smallest requested column drives the query, so sparse components are not found by scanning every slot.
     */
    public fun entities(vararg components: ComponentType<*>): List<EntityHandle> {
        components.forEach(::requireComponent)
        if (components.isEmpty()) {
            val result = ArrayList<EntityHandle>(entityCount)
            for (index in slots.indices) if (slots[index].alive) result += handle(index)
            return result
        }
        val required = arrayOfNulls<ComponentColumn>(components.size)
        var driver = -1
        for ((i, component) in components.withIndex()) {
            val column = columns[component.ordinal]
            if (column == null || column.size == 0) return emptyList()
            required[i] = column
            if (driver < 0 || column.size < checkNotNull(required[driver]).size) driver = i
        }
        val smallest = checkNotNull(required[driver])
        if (smallest.size.toLong() * SORT_COST_FACTOR >= slots.size) {
            // Dense relative to the slot range: an ordered slot scan avoids sorting.
            val result = ArrayList<EntityHandle>(smallest.size)
            for (index in slots.indices) {
                if (required.all { checkNotNull(it).contains(index) }) result += handle(index)
            }
            return result
        }
        val matches = IntArray(smallest.size)
        var count = 0
        for (position in 0 until smallest.size) {
            val index = smallest.slotAt(position)
            if (required.all { checkNotNull(it).contains(index) }) matches[count++] = index
        }
        matches.sort(0, count)
        val result = ArrayList<EntityHandle>(count)
        for (i in 0 until count) result += handle(matches[i])
        return result
    }

    /**
     * Executes one fixed tick, then commits structural commands in recorded order. Reentrant stepping is rejected.
     * Native failures are rethrown after latching [isFaulted]; this is not the future portable-program rollback contract.
     */
    public fun step() {
        check(stage == Stage.Idle && !isFaulted) { "World is already stepping or faulted" }
        check(tick < Long.MAX_VALUE) { "Tick counter exhausted" }
        val commands = WorldCommands(this) { currentSystem }
        stage = Stage.Updating
        tick++
        try {
            for ((id, system) in systems) {
                currentSystem = id
                system.update(this, commands)
            }
            stage = Stage.Committing
            commands.commit { currentSystem = it }
        } catch (failure: Throwable) {
            isFaulted = true
            failedSystem = currentSystem
            throw failure
        } finally {
            commands.close()
            stage = Stage.Idle
        }
    }

    /**
     * Captures at a healthy idle tick boundary. Every attached component needs a codec and every installed system
     * must implement [CheckpointSystem]. Pure capture failures leave engine-owned source state unchanged.
     */
    public fun snapshot(limits: SnapshotLimits = SnapshotLimits()): WorldSnapshot {
        check(stage == Stage.Idle && !isFaulted) { "Snapshots require a healthy idle world" }
        // registeredComponents is sorted by ID, keeping capture order canonical and independent of registration order.
        val populated =
            registeredComponents.mapNotNull { component ->
                columns[component.ordinal]?.takeIf { it.size > 0 }?.let { component to it }
            }
        populated.forEach { check(it.first.supportsCheckpoint) { "Component '${it.first.id}' has no checkpoint codec" } }
        systems.forEach { (id, system) -> check(system is CheckpointSystem) { "System '$id' has no checkpoint contract" } }
        val context = StateCaptureContext(this)
        val freezer = StateFreezer(owner, limits)
        stage = Stage.Capturing
        try {
            val capturedComponents =
                populated.map { (component, column) ->
                    CapturedComponent(
                        component,
                        column.sortedEntries().map { (index, value) ->
                            index to freezer.copy(component.capture(value, context))
                        },
                    )
                }
            val capturedSystems =
                systems.map { (id, system) ->
                    id to freezer.copy((system as CheckpointSystem).captureState(context))
                }
            return WorldSnapshot(
                CapturedWorld(
                    registry,
                    owner,
                    tick,
                    slots.map { CapturedSlot(it.generation, it.alive) },
                    freeSlots.toList(),
                    registeredComponents.map { it.id },
                    capturedComponents,
                    capturedSystems,
                ),
            )
        } finally {
            context.close()
            stage = Stage.Idle
        }
    }

    /** Captures and hashes the complete declared checkpoint state; unsupported native state is never omitted. */
    public fun stateHash(): Long = snapshot().stateHash()

    internal fun restoreSnapshot(state: CapturedWorld) {
        check(stage == Stage.Idle && slots.isEmpty()) { "Restore requires a fresh world" }
        val context = StateRestoreContext(this, state.origin)
        stage = Stage.Restoring
        try {
            tick = state.tick
            slots += state.slots.map { Slot(it.generation, it.alive) }
            freeSlots += state.freeSlots
            entityCount = slots.count { it.alive }
            for ((component, entries) in state.components) {
                val column = ComponentColumn()
                for ((index, value) in entries) column.put(index, component.restore(copyCapturedState(value), context))
                columns[component.ordinal] = column
            }
            for ((index, captured) in state.systems.withIndex()) {
                val (id, system) = systems[index]
                check(id == captured.first && system is CheckpointSystem) { "System '$id' has no matching checkpoint contract" }
                system.restoreState(copyCapturedState(captured.second), context)
            }
        } finally {
            context.close()
            stage = Stage.Idle
        }
    }

    internal fun requireOwnedReference(entity: EntityHandle) {
        require(entity.owner === owner && entity.index in slots.indices && entity.generation in 0..slots[entity.index].generation) {
            "Foreign or invalid entity reference"
        }
    }

    internal fun restoredReference(
        index: Int,
        generation: Long,
    ): EntityHandle = EntityHandle(owner, index, generation).also(::requireOwnedReference)

    internal fun requireEntity(entity: EntityHandle) {
        require(isAlive(entity)) { "Stale or foreign entity handle: $entity" }
    }

    internal fun requireComponent(component: ComponentType<*>) {
        require(component.registry === registry) { "Component '${component.id}' belongs to another runtime" }
    }

    private fun requireStructuralEdit() {
        check(!isFaulted) { "World is faulted" }
        check(stage == Stage.Idle || stage == Stage.Committing) { "Use WorldCommands during updates; checkpoint state is read-only" }
    }

    /** Returns the cached immutable handle for a live slot's current generation. */
    private fun handle(index: Int): EntityHandle {
        val slot = slots[index]
        return slot.handle ?: EntityHandle(owner, index, slot.generation).also { slot.handle = it }
    }

    private class Slot(
        var generation: Long = 0L,
        var alive: Boolean = true,
        /** Handle for the current generation; cleared whenever the generation changes. */
        var handle: EntityHandle? = null,
    )

    private companion object {
        /**
         * A sparse query collects the smallest column then sorts; a dense one scans slots in order. Sorting k
         * matches costs about k·log2(k) comparisons, so scan once k·16 reaches the slot range (k ≥ slots/16).
         */
        const val SORT_COST_FACTOR = 16L
    }

    private enum class Stage { Idle, Updating, Committing, Capturing, Restoring }
}
