package dev.spawnkit.runtime

/**
 * Explicit native component checkpoint contract. Capture must not mutate simulation/external state. Restore must
 * return a fresh value preserving all future-affecting state and use [StateRestoreContext] for entity references.
 * Arbitrary Kotlin aliases, external services and unregistered state are not automatically copied or sandboxed.
 */
public interface StateCodec<T : Any> {
    /** Captures the complete value as generic checkpoint data. */
    public fun capture(
        value: T,
        context: StateCaptureContext,
    ): StateValue

    /** Reconstructs a value from a private copy of its captured data. */
    public fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): T
}

/**
 * Native system opt-in: every future-affecting private field must be captured and restored. Even stateless systems
 * must explicitly implement this interface (using [StateValue.Null]); lambda systems are never assumed stateless.
 */
public interface CheckpointSystem : WorldSystem {
    /** Purely captures all private simulation state, including retained entity references. */
    public fun captureState(context: StateCaptureContext): StateValue

    /** Restores private state into the fresh system instance created by its registered factory. */
    public fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    )
}

/** A capture-scoped entity reference encoder. It expires when capture returns or fails. */
public class StateCaptureContext internal constructor(
    world: World,
) {
    private var activeWorld: World? = world

    /** Encodes a live or stale same-world handle; references to another world fail. */
    public fun entity(handle: EntityHandle): StateValue.EntityReference {
        val world = checkNotNull(activeWorld) { "State capture context has expired" }
        world.requireOwnedReference(handle)
        return StateValue.EntityReference(world.checkpointIdentity, handle.index, handle.generation)
    }

    internal fun close() {
        activeWorld = null
    }
}

/** A restore-scoped reference resolver. It expires before the restored world is returned. */
public class StateRestoreContext internal constructor(
    world: World,
    private val origin: Any,
) {
    private var activeWorld: World? = world

    /** Maps a captured reference to a handle owned by the new world, preserving stale-reference semantics. */
    public fun entity(state: StateValue): EntityHandle {
        val world = checkNotNull(activeWorld) { "State restore context has expired" }
        require(state is StateValue.EntityReference && state.origin === origin) { "Expected a captured entity reference" }
        return world.restoredReference(state.index, state.generation)
    }

    internal fun close() {
        activeWorld = null
    }
}

/** Basic codecs; extension packages can implement [StateCodec] for arbitrary native component types. */
public object StateCodecs {
    /** Exact signed 32-bit integer codec, rejecting an out-of-range restored value. */
    public val int: StateCodec<Int> =
        object : StateCodec<Int> {
            override fun capture(
                value: Int,
                context: StateCaptureContext,
            ): StateValue = StateValue.Integer(value.toLong())

            override fun restore(
                state: StateValue,
                context: StateRestoreContext,
            ): Int {
                require(state is StateValue.Integer && state.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    "Expected a signed 32-bit integer state"
                }
                return state.value.toInt()
            }
        }

    /** Exact signed 64-bit integer codec. */
    public val long: StateCodec<Long> =
        object : StateCodec<Long> {
            override fun capture(
                value: Long,
                context: StateCaptureContext,
            ): StateValue = StateValue.Integer(value)

            override fun restore(
                state: StateValue,
                context: StateRestoreContext,
            ): Long {
                require(state is StateValue.Integer) { "Expected integer state" }
                return state.value
            }
        }

    /** Double codec retaining raw bits through the in-memory checkpoint. */
    public val double: StateCodec<Double> =
        object : StateCodec<Double> {
            override fun capture(
                value: Double,
                context: StateCaptureContext,
            ): StateValue = StateValue.Decimal(value)

            override fun restore(
                state: StateValue,
                context: StateRestoreContext,
            ): Double {
                require(state is StateValue.Decimal) { "Expected decimal state" }
                return state.value
            }
        }

    /** Boolean codec. */
    public val boolean: StateCodec<Boolean> =
        object : StateCodec<Boolean> {
            override fun capture(
                value: Boolean,
                context: StateCaptureContext,
            ): StateValue = StateValue.Flag(value)

            override fun restore(
                state: StateValue,
                context: StateRestoreContext,
            ): Boolean {
                require(state is StateValue.Flag) { "Expected boolean state" }
                return state.value
            }
        }

    /** String codec, including empty strings and non-ASCII characters. */
    public val string: StateCodec<String> =
        object : StateCodec<String> {
            override fun capture(
                value: String,
                context: StateCaptureContext,
            ): StateValue = StateValue.Text(value)

            override fun restore(
                state: StateValue,
                context: StateRestoreContext,
            ): String {
                require(state is StateValue.Text) { "Expected text state" }
                return state.value
            }
        }
}
