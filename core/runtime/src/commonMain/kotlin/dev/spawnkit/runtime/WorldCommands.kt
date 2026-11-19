package dev.spawnkit.runtime

/**
 * Per-tick structural edits. Recording expires before commit and cannot escape to another tick. Handles/types
 * are checked when recorded; a target destroyed by an earlier command in the same batch is skipped at commit.
 * Native callbacks are trusted and bounded; their effects are not transactional on failure.
 */
public class WorldCommands internal constructor(
    private val world: World,
    private val currentSystem: () -> String,
) {
    private val pending = mutableListOf<Pair<String, () -> Unit>>()
    private var active = true

    /** Creates an entity at commit; [configure] may attach its components through ordinary world APIs. */
    public fun spawn(configure: World.(EntityHandle) -> Unit = {}) {
        record { world.configure(world.spawn()) }
    }

    /** Destroys a live entity after all systems have observed this tick. */
    public fun destroy(entity: EntityHandle) {
        checkActive()
        world.requireEntity(entity)
        record { if (world.isAlive(entity)) world.destroy(entity) }
    }

    /** Attaches/replaces a component at commit, using the value captured by this command. */
    public fun <T : Any> set(
        entity: EntityHandle,
        component: ComponentType<T>,
        value: T,
    ) {
        checkActive()
        world.requireEntity(entity)
        world.requireComponent(component)
        record { if (world.isAlive(entity)) world.set(entity, component, value) }
    }

    /** Detaches a component at commit. */
    public fun <T : Any> remove(
        entity: EntityHandle,
        component: ComponentType<T>,
    ) {
        checkActive()
        world.requireEntity(entity)
        world.requireComponent(component)
        record { if (world.isAlive(entity)) world.remove(entity, component) }
    }

    internal fun commit(onCommand: (String) -> Unit) {
        active = false
        pending.forEach { (system, apply) ->
            onCommand(system)
            apply()
        }
    }

    internal fun close() {
        active = false
        pending.clear()
    }

    private fun record(apply: () -> Unit) {
        checkActive()
        pending += currentSystem() to apply
    }

    private fun checkActive() {
        check(active) { "WorldCommands recording has expired" }
    }
}
