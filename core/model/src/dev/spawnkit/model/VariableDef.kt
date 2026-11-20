package dev.spawnkit.model

import kotlinx.serialization.Serializable

/**
 * A numeric game variable such as coins, score or a story flag (0 or 1).
 *
 * @property persistent kept across sessions in the player's save data.
 */
@Serializable
public data class VariableDef(
    val id: VariableId,
    val name: String,
    val initial: Double = 0.0,
    val persistent: Boolean = false,
)
