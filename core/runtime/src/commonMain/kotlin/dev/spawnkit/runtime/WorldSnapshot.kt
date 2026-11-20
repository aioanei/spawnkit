package dev.spawnkit.runtime

/**
 * Opaque, reusable, in-memory checkpoint of a healthy idle world. Its private value trees are copied at capture
 * and for every restore. It can only restore through the exact original [Runtime], not a separately built registry.
 * This is not an on-disk project format, a native-code sandbox or a portable-program transaction.
 */
public class WorldSnapshot internal constructor(
    internal val state: CapturedWorld,
) {
    /** Captured attempted tick count. */
    public val tick: Long get() = state.tick

    /** Number of live entities in the checkpoint. */
    public val entityCount: Int get() = state.slots.count { it.alive }

    /** Canonical FNV-1a regression fingerprint; non-cryptographic and independent of process/world identities. */
    public fun stateHash(): Long = hashCapturedWorld(state)
}

internal data class CapturedWorld(
    val registry: Any,
    val origin: Any,
    val tick: Long,
    val slots: List<CapturedSlot>,
    val freeSlots: List<Int>,
    val registeredComponents: List<String>,
    val components: List<CapturedComponent>,
    val systems: List<Pair<String, StateValue>>,
    /** Parent slot to ordered child slots, ascending by parent. */
    val hierarchy: List<Pair<Int, List<Int>>> = emptyList(),
    /** Resource ID to captured value, ascending by ID. */
    val resources: List<Pair<String, StateValue>> = emptyList(),
    /** Input frames, present only when the runtime registers actions or axes. */
    val input: CapturedInput? = null,
    /** Scene-instance counter and identities, present once any template was instantiated. */
    val scenes: CapturedScenes? = null,
)

internal data class CapturedSlot(
    val generation: Long,
    val alive: Boolean,
)

internal data class CapturedComponent(
    val component: ComponentType<*>,
    val entries: List<Pair<Int, StateValue>>,
)

internal data class CapturedFrame(
    val down: List<String>,
    val axes: List<Pair<String, Double>>,
    val pointers: List<Pointer>,
)

internal data class CapturedInput(
    val current: CapturedFrame,
    val previous: CapturedFrame,
    val pending: CapturedFrame?,
)

internal data class CapturedScenes(
    val counter: Long,
    val identities: List<Pair<Int, ObjectIdentity>>,
)
