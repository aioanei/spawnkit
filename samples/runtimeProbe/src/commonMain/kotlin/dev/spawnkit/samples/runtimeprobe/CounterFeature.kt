package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.CheckpointSystem
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCaptureContext
import dev.spawnkit.runtime.StateCodec
import dev.spawnkit.runtime.StateCodecs
import dev.spawnkit.runtime.StateRestoreContext
import dev.spawnkit.runtime.StateValue
import dev.spawnkit.runtime.ValueKind
import dev.spawnkit.runtime.World
import dev.spawnkit.runtime.WorldCommands

/** Example state supplied by a consumer compiled separately from the engine. */
public data class Counter(
    /** Current count. */
    public val value: Int = 0,
)

/** Installs a custom component and native system using only the runtime's public extension APIs. */
public class CounterFeature(
    builder: RuntimeBuilder,
) {
    /** Discoverable custom state; the runtime contains no knowledge of this class or identifier. */
    public val counter: ComponentType<Counter> =
        builder.component("probe:counter", AuthoringInfo("Counter", "An example counter", "Examples"), CounterCodec)

    /** Authoring metadata, so generic inspectors and agents can edit the counter without custom code. */
    public val descriptor: ComponentDescriptor<Counter> =
        builder.describe(counter) {
            create { Counter() }
            property(
                "value",
                ValueKind.WholeNumber(min = 0, max = 1_000_000),
                AuthoringInfo("Value", "Current count"),
                default = 0L,
                read = { it.value.toLong() },
                write = { _, v -> Counter(v.toInt()) },
                examples = listOf("0 to start counting from zero"),
            )
        }

    init {
        builder.system("probe:increment") { CounterSystem(counter) }
    }
}

private class CounterSystem(
    private val counter: ComponentType<Counter>,
) : CheckpointSystem {
    private var updates = 0

    override fun captureState(context: StateCaptureContext): StateValue = StateCodecs.int.capture(updates, context)

    override fun restoreState(
        state: StateValue,
        context: StateRestoreContext,
    ) {
        updates = StateCodecs.int.restore(state, context)
    }

    override fun update(
        world: World,
        commands: WorldCommands,
    ) {
        updates++
        for (entity in world.entities(counter)) {
            val previous = checkNotNull(world.get(entity, counter))
            world.set(entity, counter, Counter(previous.value + updates))
        }
    }
}

private object CounterCodec : StateCodec<Counter> {
    override fun capture(
        value: Counter,
        context: StateCaptureContext,
    ): StateValue = StateCodecs.int.capture(value.value, context)

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Counter = Counter(StateCodecs.int.restore(state, context))
}
