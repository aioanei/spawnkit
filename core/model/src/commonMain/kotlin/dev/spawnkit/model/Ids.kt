package dev.spawnkit.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Identifies a [GameProject]. */
@JvmInline
@Serializable
public value class ProjectId(
    public val value: String,
)

/** Identifies a [Scene] within a project; unique among scenes. */
@JvmInline
@Serializable
public value class SceneId(
    public val value: String,
)

/** Identifies an [EntityInstance] within a project; unique among entities. */
@JvmInline
@Serializable
public value class EntityId(
    public val value: String,
)

/** Identifies a [Prefab] within a project. */
@JvmInline
@Serializable
public value class PrefabId(
    public val value: String,
)

/** Identifies an image or other asset referenced by visuals and tiles. */
@JvmInline
@Serializable
public value class AssetId(
    public val value: String,
)

/** Identifies a game variable such as a currency or counter. */
@JvmInline
@Serializable
public value class VariableId(
    public val value: String,
)

/** Identifies a [TileLayer] within a scene. */
@JvmInline
@Serializable
public value class LayerId(
    public val value: String,
)
