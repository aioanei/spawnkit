package dev.spawnkit.model

import kotlinx.serialization.Serializable

/** The document format version written by this build. See ADR-0002 for migration rules. */
public const val CURRENT_SCHEMA_VERSION: Int = 1

/**
 * A complete game: the single source of truth edited by the creator app and AI agents and played by the runtime.
 *
 * @property tileset legend mapping single-character strings to tile definitions; `.` and space are always empty.
 * @property scenes levels in play order.
 */
@Serializable
public data class GameProject(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: ProjectId,
    val meta: ProjectMeta,
    val settings: GameSettings,
    val tileset: Map<String, TileDef> = emptyMap(),
    val variables: List<VariableDef> = emptyList(),
    val prefabs: List<Prefab> = emptyList(),
    val scenes: List<Scene>,
)

/** Human-facing information about a project. */
@Serializable
public data class ProjectMeta(
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
)

/**
 * Global game configuration.
 *
 * @property viewportTiles visible area in tiles; the screen is letterboxed to keep this aspect ratio.
 * @property tilePixels nominal pixel size of one tile for authored art.
 * @property hud variables shown in the heads-up display, in order.
 */
@Serializable
public data class GameSettings(
    val viewportTiles: Size2 = Size2(width = 20.0, height = 11.25),
    val tilePixels: Int = 32,
    val startScene: SceneId,
    val physics: PhysicsSettings = PhysicsSettings(),
    val hud: List<VariableId> = emptyList(),
)

/**
 * World physics constants.
 *
 * @property gravity downward acceleration in tiles per second squared.
 * @property maxFallSpeed terminal falling speed in tiles per second; must stay below 60 so bodies move less than one
 * tile per tick and cannot tunnel through floors.
 */
@Serializable
public data class PhysicsSettings(
    val gravity: Double = 50.0,
    val maxFallSpeed: Double = 20.0,
)

/** A level or screen. */
@Serializable
public data class Scene(
    val id: SceneId,
    val name: String,
    val size: GridSize,
    val background: ColorRgba,
    val layers: List<TileLayer> = emptyList(),
    val entities: List<EntityInstance> = emptyList(),
)

/** Looks up a scene by [id]. */
public fun GameProject.scene(id: SceneId): Scene? = scenes.firstOrNull { it.id == id }

/** Looks up a prefab by [id]. */
public fun GameProject.prefab(id: PrefabId): Prefab? = prefabs.firstOrNull { it.id == id }
