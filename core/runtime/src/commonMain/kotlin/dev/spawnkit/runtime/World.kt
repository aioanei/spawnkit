package dev.spawnkit.runtime

/** The duration in seconds of one runtime simulation tick. Frame timing is supplied by the host. */
public const val FIXED_DELTA_SECONDS: Double = 1.0 / 60.0

/**
 * Genre-independent mutable runtime state. Queries are ordered by slot, never by hash-map iteration.
 * Structural changes are immediate outside a tick and deferred through [WorldCommands] during a tick.
 * Each world is confined to one caller thread; neither mutable components nor native systems are sandboxed.
 *
 * This initial evidence implementation has no snapshots, persistence, physics or portable program executor.
 * Native systems are trusted: a failure faults the world and discards pending commands, but does not roll back
 * component writes or structural commands already committed. Inspect a faulted world; create a fresh one to run again.
 */
public class World internal constructor(
    private val registry: Any,
    private val systems: List<Pair<String, WorldSystem>>,
) {
    private val owner = Any()
    private val slots = mutableListOf<Slot>()
    private val freeSlots = mutableListOf<Int>()
    private val stores = mutableMapOf<ComponentType<*>, MutableMap<Int, Any>>()
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
        stores.values.forEach { it.remove(entity.index) }
        slot.alive = false
        slot.generation++
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
        check(!isFaulted) { "World is faulted" }
        requireEntity(entity)
        requireComponent(component)
        val existing = stores[component]
        if (existing?.containsKey(entity.index) != true) requireStructuralEdit()
        stores.getOrPut(component) { mutableMapOf() }[entity.index] = value
    }

    /** Reads a component, or `null` if absent. Stale/foreign handles and foreign component registrations fail. */
    @Suppress("UNCHECKED_CAST") // Registry identity and the typed setter constrain every store entry to T.
    public fun <T : Any> get(entity: EntityHandle, component: ComponentType<T>): T? {
        requireEntity(entity)
        requireComponent(component)
        return stores[component]?.get(entity.index) as T?
    }

    /** Detaches a component outside a system update and returns its previous value, if present. */
    @Suppress("UNCHECKED_CAST") // Same registration/type boundary as get/set.
    public fun <T : Any> remove(entity: EntityHandle, component: ComponentType<T>): T? {
        requireStructuralEdit()
        requireEntity(entity)
        requireComponent(component)
        return stores[component]?.remove(entity.index) as T?
    }

    /** Captures live handles having all [components], in ascending slot order; no types means every entity. */
    public fun entities(vararg components: ComponentType<*>): List<EntityHandle> {
        components.forEach(::requireComponent)
        return slots.indices
            .filter { index ->
                slots[index].alive && components.all { stores[it]?.containsKey(index) == true }
            }.map(::handle)
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

    internal fun requireEntity(entity: EntityHandle) {
        require(isAlive(entity)) { "Stale or foreign entity handle: $entity" }
    }

    internal fun requireComponent(component: ComponentType<*>) {
        require(component.registry === registry) { "Component '${component.id}' belongs to another runtime" }
    }

    private fun requireStructuralEdit() {
        check(!isFaulted) { "World is faulted" }
        check(stage != Stage.Updating) { "Use WorldCommands for structural edits during a system update" }
    }

    private fun handle(index: Int): EntityHandle = EntityHandle(owner, index, slots[index].generation)

    private class Slot(
        var generation: Long = 0L,
        var alive: Boolean = true,
    )

    private enum class Stage { Idle, Updating, Committing }
}
