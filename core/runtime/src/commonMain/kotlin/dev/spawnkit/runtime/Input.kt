package dev.spawnkit.runtime

/** A named on/off input such as "jump" or "interact", registered by [RuntimeBuilder.action]. */
public class InputAction internal constructor(
    /** Stable namespaced identifier. */
    public val id: String,
    /** Friendly metadata for binding and help UIs. */
    public val authoring: AuthoringInfo,
    internal val registry: Any,
)

/** A named analog input in `[-1, 1]` such as "move-x", registered by [RuntimeBuilder.axis]. */
public class InputAxis internal constructor(
    /** Stable namespaced identifier. */
    public val id: String,
    /** Friendly metadata for binding and help UIs. */
    public val authoring: AuthoringInfo,
    internal val registry: Any,
)

/**
 * A touch or mouse pointer in world units. Hosts convert screen positions through the active camera before
 * submitting, so gameplay is independent of screen size and density.
 */
public data class Pointer(
    /** Identifier, unique within a frame and stable while the pointer stays active. */
    public val id: Int,
    /** Horizontal position. */
    public val x: Double,
    /** Vertical position. */
    public val y: Double,
    /** Whether the pointer is pressed. */
    public val pressed: Boolean,
)

/**
 * Complete input state for one tick: held actions, axis values and pointers. Hosts translate keys, buttons and
 * touches into frames; the runtime never reads devices or wall-clock time, so recorded frames replay exactly.
 */
public class InputFrame(
    down: Set<InputAction> = emptySet(),
    axes: Map<InputAxis, Double> = emptyMap(),
    pointers: List<Pointer> = emptyList(),
) {
    /** Actions currently held; a private copy, so later changes to the caller's set have no effect. */
    public val down: Set<InputAction> = down.toSet()

    /** Axis values in `[-1, 1]`; absent axes read as 0. A private copy. */
    public val axes: Map<InputAxis, Double> = axes.toMap()

    /** Active pointers; a private copy. */
    public val pointers: List<Pointer> = pointers.toList()

    internal fun requireValid(registry: Any) {
        down.forEach { require(it.registry === registry) { "Input action '${it.id}' belongs to another runtime" } }
        axes.forEach { (axis, value) ->
            require(axis.registry === registry) { "Input axis '${axis.id}' belongs to another runtime" }
            require(value.isFinite() && value >= -1.0 && value <= 1.0) { "Axis '${axis.id}' must be between -1 and 1, not $value" }
        }
        pointers.forEach { require(it.x.isFinite() && it.y.isFinite()) { "Pointer ${it.id} has an invalid position" } }
        require(pointers.map { it.id }.toSet().size == pointers.size) { "Pointer IDs must be unique within a frame" }
    }

    internal companion object {
        val EMPTY = InputFrame()
    }
}

/** Read-only input for the current tick, with edges relative to the previous tick. */
public class InputView internal constructor(
    private val world: World,
) {
    /** Whether [action] is held this tick. */
    public fun isDown(action: InputAction): Boolean = world.checkedInput(action) in world.currentInput.down

    /** Whether [action] became held this tick. */
    public fun wasPressed(action: InputAction): Boolean = isDown(action) && action !in world.previousInput.down

    /** Whether [action] was released this tick. */
    public fun wasReleased(action: InputAction): Boolean = !isDown(action) && action in world.previousInput.down

    /** Value of [axis] this tick, or 0 when not supplied. */
    public fun axis(axis: InputAxis): Double {
        require(axis.registry === world.registryIdentity) { "Input axis '${axis.id}' belongs to another runtime" }
        return world.currentInput.axes[axis] ?: 0.0
    }

    /** Active pointers this tick, in the order supplied. */
    public val pointers: List<Pointer> get() = world.currentInput.pointers

    /** Pointers of the previous tick, so rules can detect presses (pressed now, not before) and releases. */
    public val previousPointers: List<Pointer> get() = world.previousInput.pointers
}
