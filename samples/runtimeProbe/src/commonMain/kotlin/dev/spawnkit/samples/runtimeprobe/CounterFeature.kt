package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.World
import dev.spawnkit.runtime.WorldCommands
import dev.spawnkit.runtime.WorldSystem

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
        builder.component("probe:counter", AuthoringInfo("Counter", "An example counter", "Examples"))

    init {
        builder.system("probe:increment") { CounterSystem(counter) }
    }
}

private class CounterSystem(
    private val counter: ComponentType<Counter>,
) : WorldSystem {
    private var updates = 0

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
