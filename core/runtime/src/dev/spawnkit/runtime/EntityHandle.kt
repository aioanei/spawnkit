package dev.spawnkit.runtime

/**
 * A world-scoped entity reference. Slot reuse changes [generation]; handles from another world or a destroyed
 * entity cannot address a new entity even when their numeric [index] matches. Handles are not persistent project IDs.
 */
public class EntityHandle internal constructor(
    internal val owner: Any,
    /** Runtime storage slot, also the stable query ordering key within this world. */
    public val index: Int,
    /** Generation of this allocation of the slot. */
    public val generation: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is EntityHandle && owner === other.owner && index == other.index && generation == other.generation

    override fun hashCode(): Int = 31 * (31 * owner.hashCode() + index) + generation.hashCode()

    override fun toString(): String = "EntityHandle(index=$index, generation=$generation)"
}
