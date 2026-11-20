package dev.spawnkit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a tile behaves in the simulation. */
@Serializable
public enum class TileBehavior {
    /** Nothing; entities pass through. */
    @SerialName("empty")
    Empty,

    /** Blocks movement from every side. */
    @SerialName("solid")
    Solid,

    /** Supports entities landing from above; passable from below and the sides. */
    @SerialName("oneWay")
    OneWay,

    /** Kills the player on contact. */
    @SerialName("hazard")
    Hazard,

    /** Drawn but has no physical effect. */
    @SerialName("decor")
    Decor,
}

/**
 * Legend entry for one tile character.
 *
 * @property behavior physical behavior.
 * @property color fill color when no [asset] is set (or while it loads).
 * @property asset optional image drawn for this tile.
 */
@Serializable
public data class TileDef(
    val behavior: TileBehavior,
    val color: ColorRgba,
    val asset: AssetId? = null,
)

/** Characters that always mean an empty cell, regardless of the tileset legend. */
public val EmptyTileChars: Set<Char> = setOf('.', ' ')

/**
 * A grid of tile characters. Row 0 is the top row; each character is looked up in [GameProject.tileset].
 *
 * @property rows one string per tile row; every row should be as wide as the scene.
 */
@Serializable
public data class TileLayer(
    val id: LayerId,
    val name: String,
    val rows: List<String>,
)

/** Returns the character at column [x], row [y], or `'.'` when the cell is outside the layer. */
public fun TileLayer.charAt(
    x: Int,
    y: Int,
): Char {
    val row = rows.getOrNull(y) ?: return '.'
    return row.getOrNull(x) ?: '.'
}

/** Checks that the layer has exactly [height] rows of exactly [width] characters; returns human-readable errors. */
public fun TileLayer.validateRows(
    width: Int,
    height: Int,
): List<String> {
    val errors = mutableListOf<String>()
    if (rows.size != height) errors += "Layer '${id.value}' has ${rows.size} rows, expected $height"
    rows.forEachIndexed { index, row ->
        if (row.length != width) errors += "Layer '${id.value}' row $index has ${row.length} columns, expected $width"
    }
    return errors
}
