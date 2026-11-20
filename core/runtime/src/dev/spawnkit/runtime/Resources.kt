package dev.spawnkit.runtime

/**
 * A typed per-world singleton, such as settings, a score table or a random generator, registered by
 * [RuntimeBuilder.resource]. Every world gets its own value from the factory. Access it with [World.resource].
 */
public class ResourceType<T : Any> internal constructor(
    /** Stable namespaced identifier. */
    public val id: String,
    /** Human-facing metadata. */
    public val authoring: AuthoringInfo,
    internal val registry: Any,
    private val checkpoint: StateCodec<T>?,
    internal val create: () -> T,
    internal val ordinal: Int,
) {
    internal val supportsCheckpoint: Boolean get() = checkpoint != null

    @Suppress("UNCHECKED_CAST") // Values enter a world's resource table only through this typed key.
    internal fun capture(
        value: Any,
        context: StateCaptureContext,
    ): StateValue = checkNotNull(checkpoint) { "Resource '$id' has no checkpoint codec" }.capture(value as T, context)

    internal fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): T = checkNotNull(checkpoint) { "Resource '$id' has no checkpoint codec" }.restore(state, context)
}

/**
 * Deterministic pseudo-random numbers (SplitMix64). It uses only wrapping 64-bit integer arithmetic and one exact
 * power-of-two scaling, so the same seed yields the same sequence on every target. Not cryptographically secure.
 */
public class SeededRandom(
    seed: Long,
) {
    private var state: Long = seed

    /** Next uniformly distributed 64-bit value. */
    public fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** Uniform value in `0 until bound` without modulo bias. */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "Bound must be positive" }
        val range = bound.toLong()
        val limit = (1L shl 31) - (1L shl 31) % range
        while (true) {
            val candidate = nextLong() ushr 33
            if (candidate < limit) return (candidate % range).toInt()
        }
    }

    /** Uniform value in `[0, 1)` with 53 random bits. */
    public fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * DOUBLE_UNIT

    /** Checkpoint codec capturing the complete generator state. */
    public companion object {
        private const val GOLDEN_GAMMA = -7046029254386353131L
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        /** Captures and restores the exact generator position. */
        public val codec: StateCodec<SeededRandom> =
            object : StateCodec<SeededRandom> {
                override fun capture(
                    value: SeededRandom,
                    context: StateCaptureContext,
                ): StateValue = StateValue.Integer(value.state)

                override fun restore(
                    state: StateValue,
                    context: StateRestoreContext,
                ): SeededRandom = SeededRandom(0L).also { it.state = StateCodecs.long.restore(state, context) }
            }
    }
}
