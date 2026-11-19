package dev.spawnkit.engine

import kotlin.jvm.JvmInline

/** Abstract player actions; platforms map keys, buttons and touches onto these. */
public enum class InputAction {
    /** Move left. */
    Left,

    /** Move right. */
    Right,

    /** Up / climb / interact upwards. */
    Up,

    /** Down / crouch / drop. */
    Down,

    /** Jump. */
    Jump,

    /** Context action (tap, use, confirm). */
    Action,
}

/** The set of actions held during one simulation tick, packed into a bit mask. */
@JvmInline
public value class InputFrame(
    public val bits: Int,
) {
    /** Whether [action] is held in this frame. */
    public fun isDown(action: InputAction): Boolean = bits and action.mask != 0

    /** Returns a frame that also holds [action]. */
    public operator fun plus(action: InputAction): InputFrame = InputFrame(bits or action.mask)

    /** Factory helpers. */
    public companion object {
        /** No action held. */
        public val NONE: InputFrame = InputFrame(0)

        /** A frame holding all [actions]. */
        public fun of(vararg actions: InputAction): InputFrame = InputFrame(actions.fold(0) { acc, action -> acc or action.mask })
    }
}

private val InputAction.mask: Int get() = 1 shl ordinal
