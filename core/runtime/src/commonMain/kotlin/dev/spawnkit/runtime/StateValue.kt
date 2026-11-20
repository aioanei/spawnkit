package dev.spawnkit.runtime

/**
 * Format-independent checkpoint values, not an authoring document or a vocabulary of gameplay mechanics.
 * Mutable input collections are defensively copied into an opaque [WorldSnapshot] and copied again for restore.
 */
public sealed interface StateValue {
    /** Explicit absence of a value or an explicitly stateless system. */
    public data object Null : StateValue

    /** A signed 64-bit integer. */
    public data class Integer(
        /** Captured integer payload. */
        public val value: Long,
    ) : StateValue

    /** A floating-point value whose raw IEEE-754 bits participate in hashing. */
    public data class Decimal(
        /** Captured floating-point payload. */
        public val value: Double,
    ) : StateValue

    /** A boolean. */
    public data class Flag(
        /** Captured boolean payload. */
        public val value: Boolean,
    ) : StateValue

    /** A string; hashing preserves its UTF-16 code units. */
    public data class Text(
        /** Captured string payload. */
        public val value: String,
    ) : StateValue

    /** An ordered collection of checkpoint values. */
    public data class Sequence(
        /** Ordered input values, defensively copied when captured. */
        public val values: List<StateValue>,
    ) : StateValue

    /** Named fields, canonicalized by lexical key order when captured. */
    public data class Record(
        /** Input fields, defensively copied and sorted when captured. */
        public val fields: Map<String, StateValue>,
    ) : StateValue

    /** An entity identity created by [StateCaptureContext.entity] and resolved by [StateRestoreContext.entity]. */
    public class EntityReference internal constructor(
        internal val origin: Any,
        /** Original slot; it is not a persistent project ID. */
        public val index: Int,
        /** Generation, including a stale generation when the reference is deliberately retained. */
        public val generation: Long,
    ) : StateValue
}

/** Limits checkpoint value-tree traversal; these do not sandbox arbitrary native codec code. */
public data class SnapshotLimits(
    /** Maximum nested value depth, between 1 and 256. Cyclic input eventually fails this limit. */
    public val maxDepth: Int = 64,
    /** Maximum total values copied from component/system codecs into a snapshot. */
    public val maxValues: Int = 1_000_000,
) {
    init {
        require(maxDepth in 1..256) { "Snapshot depth must be between 1 and 256" }
        require(maxValues > 0) { "Snapshot value budget must be positive" }
    }
}

internal class StateFreezer(
    private val origin: Any,
    private val limits: SnapshotLimits,
) {
    private var copied = 0

    fun copy(
        value: StateValue,
        depth: Int = 0,
    ): StateValue {
        require(depth <= limits.maxDepth) { "Snapshot value depth exceeded ${limits.maxDepth}" }
        require(copied < limits.maxValues) { "Snapshot value budget exceeded ${limits.maxValues}" }
        copied++
        return when (value) {
            StateValue.Null -> {
                value
            }

            is StateValue.Integer -> {
                value
            }

            is StateValue.Decimal -> {
                value
            }

            is StateValue.Flag -> {
                value
            }

            is StateValue.Text -> {
                value
            }

            is StateValue.Sequence -> {
                StateValue.Sequence(value.values.map { copy(it, depth + 1) })
            }

            is StateValue.Record -> {
                StateValue.Record(value.fields.toSortedEntries().associate { it.key to copy(it.value, depth + 1) })
            }

            is StateValue.EntityReference -> {
                require(value.origin === origin) { "Checkpoint contains a foreign entity reference" }
                StateValue.EntityReference(origin, value.index, value.generation)
            }
        }
    }
}

internal fun copyCapturedState(value: StateValue): StateValue =
    when (value) {
        is StateValue.Sequence -> StateValue.Sequence(value.values.map(::copyCapturedState))
        is StateValue.Record -> StateValue.Record(value.fields.toSortedEntries().associate { it.key to copyCapturedState(it.value) })
        is StateValue.EntityReference -> StateValue.EntityReference(value.origin, value.index, value.generation)
        else -> value
    }

internal fun Map<String, StateValue>.toSortedEntries(): List<Map.Entry<String, StateValue>> = entries.sortedBy { it.key }
