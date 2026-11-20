package dev.spawnkit.engine

import dev.spawnkit.model.TileBehavior

/**
 * Merged tile behaviors of all layers of a scene. Outside the scene, the left and right sides behave as walls while
 * above and below are open (so the player can jump above the top edge and fall out at the bottom).
 */
internal class TileGrid(
    val width: Int,
    val height: Int,
    private val cells: Array<TileBehavior>,
) {
    fun behavior(
        x: Int,
        y: Int,
    ): TileBehavior =
        when {
            x < 0 || x >= width -> TileBehavior.Solid
            y < 0 || y >= height -> TileBehavior.Empty
            else -> cells[y * width + x]
        }

    fun isSolid(
        x: Int,
        y: Int,
    ): Boolean = behavior(x, y) == TileBehavior.Solid

    fun supportsFromAbove(
        x: Int,
        y: Int,
    ): Boolean = behavior(x, y).let { it == TileBehavior.Solid || it == TileBehavior.OneWay }
}
