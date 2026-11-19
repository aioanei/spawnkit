package dev.spawnkit.render

import dev.spawnkit.engine.RuntimeWorld

/** Keeps the previous simulation positions outside the engine for interpolated rendering. */
internal class RenderPositions {
    private var world: RuntimeWorld? = null
    private var previousX = DoubleArray(0)
    private var previousY = DoubleArray(0)

    fun capture(world: RuntimeWorld) {
        if (this.world !== world) {
            this.world = world
            previousX = DoubleArray(world.entityCount)
            previousY = DoubleArray(world.entityCount)
        }
        for (i in 0 until world.entityCount) {
            previousX[i] = world.x(i)
            previousY[i] = world.y(i)
        }
    }

    fun x(
        world: RuntimeWorld,
        i: Int,
        alpha: Double,
    ): Double = if (this.world !== world) world.x(i) else previousX[i] + (world.x(i) - previousX[i]) * alpha

    fun y(
        world: RuntimeWorld,
        i: Int,
        alpha: Double,
    ): Double = if (this.world !== world) world.y(i) else previousY[i] + (world.y(i) - previousY[i]) * alpha
}
