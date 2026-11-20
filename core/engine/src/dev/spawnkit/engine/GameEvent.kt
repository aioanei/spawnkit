package dev.spawnkit.engine

import dev.spawnkit.model.EntityId
import dev.spawnkit.model.VariableId

/** Something notable that happened during a simulation tick; used by the UI, sound, rules and validators. */
public sealed interface GameEvent {
    /** The player collected [entity], adding [amount] to [variable]. */
    public data class Collected(
        val entity: EntityId,
        val variable: VariableId,
        val amount: Double,
    ) : GameEvent

    /** The player died and was moved back to the last checkpoint (or the spawn point). */
    public data object PlayerDied : GameEvent

    /** The player touched [entity] for the first time; it is the new respawn point. */
    public data class CheckpointReached(
        val entity: EntityId,
    ) : GameEvent

    /** The player reached a goal; the simulation of this level stops. */
    public data object LevelCompleted : GameEvent

    /** The player stomped on the patrolling enemy [entity]. */
    public data class EnemyDefeated(
        val entity: EntityId,
    ) : GameEvent
}
