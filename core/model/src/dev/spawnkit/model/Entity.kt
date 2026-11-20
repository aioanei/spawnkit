package dev.spawnkit.model

import kotlinx.serialization.Serializable

/**
 * An object placed in a scene.
 *
 * @property position top-left corner of the entity in world units.
 * @property prefab optional template whose components are inherited; components listed here replace inherited
 * components of the same kind.
 * @property tags free-form labels used by rules and validators.
 */
@Serializable
public data class EntityInstance(
    val id: EntityId,
    val name: String,
    val position: Vec2,
    val prefab: PrefabId? = null,
    val tags: Set<String> = emptySet(),
    val components: List<Component> = emptyList(),
)

/** A reusable entity template, e.g. "Coin" or "Slime". */
@Serializable
public data class Prefab(
    val id: PrefabId,
    val name: String,
    val tags: Set<String> = emptySet(),
    val components: List<Component>,
)
