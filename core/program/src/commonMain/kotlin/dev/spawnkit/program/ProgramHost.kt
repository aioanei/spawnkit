package dev.spawnkit.program

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.World

/** A host function failed in a declared way; the calling segment faults and rolls back. */
public class ProgramHostException(
    message: String,
) : RuntimeException(message)

/** Read-only view given to host functions. Reads see the calling segment's buffered property writes. */
public interface HostCallContext {
    /** Arguments, already type-checked against the declaration. */
    public val arguments: List<ProgramValue>

    /** Entity running the program. */
    public val self: EntityHandle

    /** Current fixed tick. */
    public val tick: Long

    /** Reads a registered property, or `null` when the entity is stale or lacks the component. */
    public fun read(
        entity: EntityHandle,
        property: String,
    ): ProgramValue?
}

/** Exposes one component's value to programs as a typed property. */
public class PropertyBinding<T : Any> internal constructor(
    /** Program-visible property name. */
    public val name: String,
    /** Component that holds the property. */
    public val component: ComponentType<T>,
    /** Property type. */
    public val type: ProgramType,
    /** Friendly metadata for editors. */
    public val authoring: AuthoringInfo,
    private val reader: (T) -> ProgramValue,
    private val writer: (T, ProgramValue) -> T,
) {
    internal fun read(
        world: World,
        entity: EntityHandle,
    ): ProgramValue? = if (world.isAlive(entity)) world.get(entity, component)?.let(reader) else null

    internal fun write(
        world: World,
        entity: EntityHandle,
        value: ProgramValue,
    ) {
        if (!world.isAlive(entity)) return
        val current = world.get(entity, component) ?: return
        world.set(entity, component, writer(current, value))
    }
}

/** A typed native function callable from programs. */
public class HostFunction internal constructor(
    /** Program-visible name. */
    public val name: String,
    /** Parameter types. */
    public val parameters: List<ProgramType>,
    /** Result type. */
    public val returns: ProgramType,
    /** Deterministic fuel charge per call, in addition to the call instruction. */
    public val cost: Int,
    /** Friendly metadata for editors. */
    public val authoring: AuthoringInfo,
    internal val implementation: (HostCallContext) -> ProgramValue,
)

/** A registered event name and its payload type ([NothingType] for none). */
public data class EventDeclaration(
    /** Event name. */
    public val name: String,
    /** Payload type. */
    public val payload: ProgramType,
)

/** A registered external effect name and its payload type. */
public data class EffectDeclaration(
    /** Effect name. */
    public val name: String,
    /** Payload type. */
    public val payload: ProgramType,
)

/** Execution limits applied to every segment and instance. All limits are deterministic counts, not wall-clock time. */
public data class ProgramLimits(
    /** Fuel per segment: one per instruction plus host-function costs. */
    public val fuelPerSegment: Long = 100_000,
    /** Maximum nested function calls. */
    public val maxCallDepth: Int = 64,
    /** List elements that may be created per segment. */
    public val allocationPerSegment: Long = 100_000,
    /** Maximum elements in one list. */
    public val maxListSize: Int = 10_000,
    /** Maximum suspended threads per instance. */
    public val maxSuspendedThreads: Int = 32,
    /** Maximum events plus effect requests per segment. */
    public val maxMessagesPerSegment: Int = 256,
) {
    init {
        require(fuelPerSegment > 0 && maxCallDepth > 0 && allocationPerSegment > 0) { "Limits must be positive" }
        require(maxListSize > 0 && maxSuspendedThreads > 0 && maxMessagesPerSegment > 0) { "Limits must be positive" }
    }
}

/** The capabilities installed packages expose to programs: properties, host functions, events and effects. */
public class ProgramHost internal constructor(
    internal val properties: List<PropertyBinding<*>>,
    internal val functions: List<HostFunction>,
    internal val events: List<EventDeclaration>,
    internal val effects: List<EffectDeclaration>,
    /** Limits for all programs run against this host. */
    public val limits: ProgramLimits,
) {
    internal val propertyIndex: Map<String, Int> = properties.withIndex().associate { it.value.name to it.index }
    internal val functionIndex: Map<String, Int> = functions.withIndex().associate { it.value.name to it.index }
    internal val eventsByName: Map<String, EventDeclaration> = events.associateBy { it.name }
    internal val effectsByName: Map<String, EffectDeclaration> = effects.associateBy { it.name }

    /** Registered property bindings, for catalogs and editors. */
    public val propertyCatalog: List<PropertyBinding<*>> get() = properties.toList()

    /** Registered host functions, for catalogs and editors. */
    public val functionCatalog: List<HostFunction> get() = functions.toList()
}

/** Builds a [ProgramHost]. Names are unique per kind. */
public class ProgramHostBuilder(
    private val limits: ProgramLimits = ProgramLimits(),
) {
    private val properties = mutableListOf<PropertyBinding<*>>()
    private val functions = mutableListOf<HostFunction>()
    private val events = mutableListOf<EventDeclaration>()
    private val effects = mutableListOf<EffectDeclaration>()

    /** Binds [component] as property [name] with explicit conversions. [write] returns the updated component value. */
    public fun <T : Any> property(
        name: String,
        component: ComponentType<T>,
        type: ProgramType,
        authoring: AuthoringInfo,
        read: (T) -> ProgramValue,
        write: (T, ProgramValue) -> T,
    ): ProgramHostBuilder =
        apply {
            requireName(name, properties.map { it.name })
            properties += PropertyBinding(name, component, type, authoring, read, write)
        }

    /** Registers a host function. [implementation] must be deterministic and bounded; throw [ProgramHostException] to fail. */
    public fun function(
        name: String,
        parameters: List<ProgramType>,
        returns: ProgramType,
        authoring: AuthoringInfo,
        cost: Int = 1,
        implementation: (HostCallContext) -> ProgramValue,
    ): ProgramHostBuilder =
        apply {
            requireName(name, functions.map { it.name })
            require(cost >= 0) { "Host function cost must not be negative" }
            functions += HostFunction(name, parameters.toList(), returns, cost, authoring, implementation)
        }

    /** Declares an event that programs may emit and handle. */
    public fun event(
        name: String,
        payload: ProgramType = NothingType,
    ): ProgramHostBuilder =
        apply {
            requireName(name, events.map { it.name })
            events += EventDeclaration(name, payload)
        }

    /** Declares an external effect that programs may request. */
    public fun effect(
        name: String,
        payload: ProgramType,
    ): ProgramHostBuilder =
        apply {
            requireName(name, effects.map { it.name })
            effects += EffectDeclaration(name, payload)
        }

    /** Freezes the registrations. */
    public fun build(): ProgramHost = ProgramHost(properties.toList(), functions.toList(), events.toList(), effects.toList(), limits)

    private fun requireName(
        name: String,
        existing: List<String>,
    ) {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid program name '$name'" }
        require(name !in existing) { "Duplicate registration '$name'" }
    }
}
