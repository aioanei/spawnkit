package dev.spawnkit.engine

/**
 * Axis-separated movement of an entity's collision box against the tile grid. Callers must keep |delta| below one
 * tile per step (guaranteed by [dev.spawnkit.model.PhysicsSettings.maxFallSpeed] < 60 and sane move speeds), so only
 * the leading edge needs to be tested and bodies cannot tunnel through tiles.
 */
internal object TilePhysics {
    /** Moves entity [i] horizontally by [dx]; returns `true` if a solid tile stopped it. */
    fun moveX(
        world: RuntimeWorld,
        i: Int,
        dx: Double,
    ): Boolean {
        val s = world.entities
        val width = s.bodyWidth[i]
        var left = s.left(i) + dx
        val rowFrom = floorInt(s.top(i) + EPSILON)
        val rowTo = floorInt(s.bottom(i) - EPSILON)
        var blocked = false
        if (dx > 0.0) {
            val column = floorInt(left + width - EPSILON)
            if ((rowFrom..rowTo).any { world.grid.isSolid(column, it) }) {
                left = column - width
                blocked = true
            }
        } else if (dx < 0.0) {
            val column = floorInt(left + EPSILON)
            if ((rowFrom..rowTo).any { world.grid.isSolid(column, it) }) {
                left = column + 1.0
                blocked = true
            }
        }
        s.x[i] = left - s.bodyOffsetX[i]
        if (blocked) s.vx[i] = 0.0
        return blocked
    }

    /**
     * Moves entity [i] vertically by [dy]. Falling bodies land on solid tiles and on one-way tiles they were above
     * before the move; rising bodies are stopped by solid tiles only. Updates the grounded flag.
     */
    fun moveY(
        world: RuntimeWorld,
        i: Int,
        dy: Double,
    ) {
        val s = world.entities
        val height = s.bodyHeight[i]
        val previousBottom = s.bottom(i)
        var top = s.top(i) + dy
        val columnFrom = floorInt(s.left(i) + EPSILON)
        val columnTo = floorInt(s.right(i) - EPSILON)
        var landed = false
        if (dy > 0.0) {
            val row = floorInt(top + height - EPSILON)
            val supported =
                (columnFrom..columnTo).any { column ->
                    world.grid.isSolid(column, row) ||
                        (world.grid.supportsFromAbove(column, row) && previousBottom <= row + EPSILON)
                }
            if (supported) {
                top = row - height
                landed = true
            }
        } else if (dy < 0.0) {
            val row = floorInt(top + EPSILON)
            if ((columnFrom..columnTo).any { world.grid.isSolid(it, row) }) {
                top = row + 1.0
                s.vy[i] = 0.0
            }
        }
        s.y[i] = top - s.bodyOffsetY[i]
        if (landed) s.vy[i] = 0.0
        s.grounded[i] = landed
    }
}
