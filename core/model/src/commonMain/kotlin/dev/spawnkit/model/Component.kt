package dev.spawnkit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A piece of data that gives an entity appearance or behavior. Entities are composed of components; the runtime's
 * systems interpret them. Serialized polymorphically with the discriminator `kind` (see ADR-0002).
 */
@Serializable
public sealed interface Component

/** How the entity is drawn. Without [asset], the [shape] is filled with [color]. */
@Serializable
@SerialName("visual")
public data class Visual(
    val size: Size2,
    val color: ColorRgba,
    val asset: AssetId? = null,
    val shape: Shape = Shape.RoundedRect,
) : Component

/** The entity's collision box, relative to its position. */
@Serializable
@SerialName("body")
public data class Body(
    val size: Size2,
    val offset: Vec2 = Vec2.Zero,
) : Component

/**
 * Makes the entity the player character of a platformer scene.
 *
 * @property moveSpeed horizontal speed in tiles per second.
 * @property jumpVelocity initial upward speed of a jump in tiles per second.
 * @property coyoteTicks ticks after leaving the ground during which a jump is still allowed.
 * @property jumpBufferTicks ticks a jump press is remembered before landing.
 */
@Serializable
@SerialName("player")
public data class PlayerController(
    val moveSpeed: Double = 8.0,
    val jumpVelocity: Double = 17.0,
    val coyoteTicks: Int = 6,
    val jumpBufferTicks: Int = 6,
) : Component

/** Collected on contact with the player: adds [amount] to [variable] and removes the entity. */
@Serializable
@SerialName("collectible")
public data class Collectible(
    val variable: VariableId,
    val amount: Double = 1.0,
) : Component

/** Kills the player on contact. */
@Serializable
@SerialName("hazard")
public data object Hazard : Component

/**
 * Walks back and forth; touching it from the side kills the player, landing on it defeats it.
 *
 * @property speed horizontal speed in tiles per second.
 * @property turnAtEdges turn around instead of walking off a ledge.
 */
@Serializable
@SerialName("patrol")
public data class Patrol(
    val speed: Double = 2.5,
    val turnAtEdges: Boolean = true,
) : Component

/** When the player touches it, it becomes the respawn point. */
@Serializable
@SerialName("checkpoint")
public data object Checkpoint : Component

/** When the player touches it, the level is completed. */
@Serializable
@SerialName("goal")
public data object Goal : Component
