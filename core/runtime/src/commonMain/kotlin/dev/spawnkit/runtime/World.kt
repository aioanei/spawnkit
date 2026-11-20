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
    private val registeredResources: List<ResourceType<*>> = emptyList(),
    private val registeredActions: List<InputAction> = emptyList(),
    private val registeredAxes: List<InputAxis> = emptyList(),
) {
    private val owner = Any()
    private val resources: Array<Any> = Array(registeredResources.size) { registeredResources[it].create() }
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

    internal val registryIdentity: Any get() = registry

    internal var currentInput: InputFrame = InputFrame.EMPTY
    internal var previousInput: InputFrame = InputFrame.EMPTY
    private var pendingInput: InputFrame? = null

    /** Input for the current tick; see [submitInput]. */
    public val input: InputView = InputView(this)

    /**
     * Supplies the input frame for the next [step]. Only allowed between ticks on a healthy world. Without a new
     * submission, the previous frame stays in effect, so held input persists across ticks.
     */
    public fun submitInput(frame: InputFrame) {
        check(stage == Stage.Idle && !isFaulted) { "Input can only be submitted between ticks on a healthy world" }
        frame.requireValid(registry)
        pendingInput = frame
    }

    internal fun checkedInput(action: InputAction): InputAction {
        require(action.registry === registry) { "Input action '${action.id}' belongs to another runtime" }
        return action
    }

    /** Whether authoring edits may apply now: a healthy world between ticks. */
    internal val acceptsAuthoringEdits: Boolean get() = !isFaulted && stage == Stage.Idle

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

    /**
     * Removes an entity and its components; stale or foreign handles are rejected. It is detached from its parent;
     * its children become roots, keeping their order. Use [destroyTree] to remove descendants too.
     */
    public fun destroy(entity: EntityHandle) {
        requireStructuralEdit()
        requireEntity(entity)
        val slot = slots[entity.index]
        check(slot.generation < Long.MAX_VALUE) { "Entity generation exhausted" }
        detach(entity.index)
        slot.children?.forEach { slots[it].parent = NO_PARENT }
        slot.children = null
        for (column in columns) column?.remove(entity.index)
        slot.alive = false
        slot.generation++
        slot.handle = null
        freeSlots += entity.index
        entityCount--
    }

    /** Destroys [entity] and all its descendants, children before parents, in child order. */
    public fun destroyTree(entity: EntityHandle) {
        requireStructuralEdit()
        requireEntity(entity)
        for (child in children(entity)) destroyTree(child)
        destroy(entity)
    }

    /** The parent of [entity], or `null` for a root. */
    public fun parent(entity: EntityHandle): EntityHandle? {
        requireEntity(entity)
        val parent = slots[entity.index].parent
        return if (parent == NO_PARENT) null else handle(parent)
    }

    /** Children of [entity] in their authored order. */
    public fun children(entity: EntityHandle): List<EntityHandle> {
        requireEntity(entity)
        return slots[entity.index].children?.map(::handle) ?: emptyList()
    }

    /**
     * Makes [child] a child of [parent] at position [index] (default: last), or a root when [parent] is `null`.
     * Reparenting within the same parent reorders. Self-parenting and cycles are rejected.
     */
    public fun setParent(
        child: EntityHandle,
        parent: EntityHandle?,
        index: Int = -1,
    ) {
        requireStructuralEdit()
        requireEntity(child)
        require(index >= -1) { "Child position $index is invalid; use -1 to append" }
        if (parent != null) {
            requireEntity(parent)
            var cursor = parent.index
            while (cursor != NO_PARENT) {
                require(cursor != child.index) { "An object cannot be placed inside itself or its own descendants" }
                cursor = slots[cursor].parent
            }
        }
        if (parent != null && index >= 0) {
            // Validate before detaching, so a rejected edit changes nothing.
            val existing = slots[parent.index].children?.size ?: 0
            val available = if (slots[child.index].parent == parent.index) existing - 1 else existing
            require(index <= available) { "Child position $index is outside 0..$available" }
        }
        detach(child.index)
        if (parent == null) return
        val siblings = slots[parent.index].children ?: mutableListOf<Int>().also { slots[parent.index].children = it }
        siblings.add(if (index < 0) siblings.size else index, child.index)
        slots[child.index].parent = parent.index
    }

    /** Returns the world's value of a registered resource. */
    @Suppress("UNCHECKED_CAST") // Each table entry was created by its own typed resource factory.
    public fun <T : Any> resource(type: ResourceType<T>): T {
        require(type.registry === registry) { "Resource '${type.id}' belongs to another runtime" }
        return resources[type.ordinal] as T
    }

    private fun detach(index: Int) {
        val slot = slots[index]
        if (slot.parent == NO_PARENT) return
        val siblings = checkNotNull(slots[slot.parent].children)
        siblings.remove(index)
        if (siblings.isEmpty()) slots[slot.parent].children = null
        slot.parent = NO_PARENT
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
        if (smallest.size.toLong() * ComponentColumn.SORT_COST_FACTOR >= slots.size) {
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
        previousInput = currentInput
        currentInput = pendingInput ?: currentInput
        pendingInput = null
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
        registeredResources.forEach { check(it.supportsCheckpoint) { "Resource '${it.id}' has no checkpoint codec" } }
        val context = StateCaptureContext(this)
        val freezer = StateFreezer(owner, limits)
        stage = Stage.Capturing
        try {
            val capturedComponents =
                populated.map { (component, column) ->
                    CapturedComponent(
                        component,
                        ArrayList<Pair<Int, StateValue>>(column.size).also { entries ->
                            column.forEachInSlotOrder { index, value ->
                                entries += index to freezer.copy(component.capture(value, context))
                            }
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
                    slots.indices.mapNotNull { index -> slots[index].children?.let { index to it.toList() } },
                    registeredResources.sortedBy { it.id }.map { it.id to freezer.copy(it.capture(resources[it.ordinal], context)) },
                    if (registeredActions.isEmpty() && registeredAxes.isEmpty() && !hasPointerInput()) {
                        null
                    } else {
                        CapturedInput(capture(currentInput), capture(previousInput), pendingInput?.let(::capture))
                    },
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
                val column = ComponentColumn(entries.size, slots.size)
                for ((index, value) in entries) column.put(index, component.restore(copyCapturedState(value), context))
                columns[component.ordinal] = column
            }
            for ((index, captured) in state.systems.withIndex()) {
                val (id, system) = systems[index]
                check(id == captured.first && system is CheckpointSystem) { "System '$id' has no matching checkpoint contract" }
                system.restoreState(copyCapturedState(captured.second), context)
            }
            for ((parent, children) in state.hierarchy) {
                slots[parent].children = children.toMutableList()
                children.forEach { slots[it].parent = parent }
            }
            state.input?.let {
                currentInput = restoreFrame(it.current)
                previousInput = restoreFrame(it.previous)
                pendingInput = it.pending?.let(::restoreFrame)
            }
            for ((id, value) in state.resources) {
                val type = registeredResources.first { it.id == id }
                resources[type.ordinal] = type.restore(copyCapturedState(value), context)
            }
        } finally {
            context.close()
            stage = Stage.Idle
        }
    }

    private fun hasPointerInput(): Boolean =
        currentInput.pointers.isNotEmpty() || previousInput.pointers.isNotEmpty() || pendingInput?.pointers?.isNotEmpty() == true

    internal fun restoreInputFrame(frame: CapturedFrame): InputFrame =
        InputFrame(
            frame.down.map { id -> registeredActions.first { it.id == id } }.toSet(),
            frame.axes.associate { (id, value) -> registeredAxes.first { it.id == id } to value },
            frame.pointers,
        )

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
        /** Parent slot, or [NO_PARENT]. */
        var parent: Int = NO_PARENT,
        /** Ordered child slots, or `null` when there are none. */
        var children: MutableList<Int>? = null,
    )

    private enum class Stage { Idle, Updating, Committing, Capturing, Restoring }

    private companion object {
        const val NO_PARENT = -1
    }
}

private fun capture(frame: InputFrame): CapturedFrame =
    CapturedFrame(
        frame.down.map { it.id }.sorted(),
        frame.axes.entries
            .map { it.key.id to it.value }
            .sortedBy { it.first },
        frame.pointers.toList(),
    )

private fun World.restoreFrame(frame: CapturedFrame): InputFrame = restoreInputFrame(frame)
