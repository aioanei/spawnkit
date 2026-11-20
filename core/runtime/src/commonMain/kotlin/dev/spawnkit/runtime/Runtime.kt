package dev.spawnkit.runtime

/** Ordered fixed-tick phases. Presentation is a separate consumer, not a mandatory runtime system. */
public enum class SystemPhase {
    /** Work that must precede ordinary simulation. */
    BeforeUpdate,

    /** Ordinary simulation work. */
    Update,

    /** Work that observes all ordinary simulation updates. */
    AfterUpdate,
}

/** A per-world system. Implementations are trusted Kotlin code and must finish each call in bounded time. */
public fun interface WorldSystem {
    /** Updates one fixed tick; use [commands] for structural changes and [World.set] for existing values. */
    public fun update(
        world: World,
        commands: WorldCommands,
    )
}

/** Builds an immutable runtime registration set. Registration closes after a successful [build]. */
public class RuntimeBuilder {
    private val registry = Any()
    private val components = mutableMapOf<String, ComponentType<*>>()
    private val systems = mutableMapOf<String, SystemRegistration>()
    private var built = false

    /**
     * Registers an open Kotlin component type; duplicate IDs and registration after build are rejected.
     * [checkpoint] is optional for ordinary execution but required when an attached value is captured.
     */
    public fun <T : Any> component(
        id: String,
        authoring: AuthoringInfo,
        checkpoint: StateCodec<T>? = null,
    ): ComponentType<T> {
        check(!built) { "Runtime registration has closed" }
        requireCapabilityId(id)
        require(id !in components) { "Duplicate component '$id'" }
        return ComponentType<T>(id, authoring, registry, checkpoint).also { components[id] = it }
    }

    /**
     * Registers a system factory. It must return fresh mutable state for each world. Dependencies reference system
     * IDs; phases take precedence, contradictory dependencies fail at build, and independent ties use lexical IDs.
     */
    public fun system(
        id: String,
        phase: SystemPhase = SystemPhase.Update,
        before: Set<String> = emptySet(),
        after: Set<String> = emptySet(),
        create: () -> WorldSystem,
    ) {
        check(!built) { "Runtime registration has closed" }
        requireCapabilityId(id)
        require(id !in systems) { "Duplicate system '$id'" }
        systems[id] = SystemRegistration(id, phase, before.toSet(), after.toSet(), create)
    }

    /** Validates the complete schedule and freezes registrations. No game document or player is involved. */
    public fun build(): Runtime {
        check(!built) { "Runtime registration has closed" }
        val schedule = orderSystems(systems.values.toList())
        built = true
        return Runtime(registry, components.values.sortedBy { it.id }, schedule)
    }
}

/** An immutable set of installed runtime capabilities that can instantiate independent headless worlds. */
public class Runtime internal constructor(
    private val registry: Any,
    private val components: List<ComponentType<*>>,
    private val schedule: List<SystemRegistration>,
) {
    /** Effective fixed-tick system order; returned as a copy so callers cannot mutate the installed schedule. */
    public val systemOrder: List<String> get() = schedule.map { it.id }

    /** Constructs an empty world and fresh system instances. Component instances are supplied through [World.set]. */
    public fun createWorld(): World = World(registry, components, schedule.map { it.id to it.create() })

    /**
     * Restores into fresh world/system instances from this runtime's checkpoint. Repeated restores are independent;
     * failure publishes no world and does not consume the checkpoint. Native callbacks must obey their state contracts.
     */
    public fun restore(snapshot: WorldSnapshot): World {
        require(snapshot.state.registry === registry) { "Snapshot belongs to another runtime" }
        return createWorld().also { it.restoreSnapshot(snapshot.state) }
    }
}

internal data class SystemRegistration(
    val id: String,
    val phase: SystemPhase,
    val before: Set<String>,
    val after: Set<String>,
    val create: () -> WorldSystem,
)

private fun orderSystems(systems: List<SystemRegistration>): List<SystemRegistration> {
    val byId = systems.associateBy { it.id }
    val predecessors = systems.associate { it.id to mutableSetOf<String>() }
    for (system in systems) {
        for (dependency in (system.before + system.after).sorted()) {
            require(dependency in byId) { "System '${system.id}' references missing system '$dependency'" }
        }
        system.before.forEach { predecessors.getValue(it).add(system.id) }
        system.after.forEach { predecessors.getValue(system.id).add(it) }
        systems.filter { it.phase < system.phase }.forEach { predecessors.getValue(system.id).add(it.id) }
    }
    val ordered = mutableListOf<SystemRegistration>()
    val remaining = systems.map { it.id }.toMutableSet()
    while (remaining.isNotEmpty()) {
        val next = remaining.filter { predecessors.getValue(it).isEmpty() }.minOrNull()
        require(next != null) { "Cyclic or phase-contradicting system order: ${remaining.sorted().joinToString()}" }
        ordered += byId.getValue(next)
        remaining.remove(next)
        predecessors.values.forEach { it.remove(next) }
    }
    return ordered
}
