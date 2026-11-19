package dev.spawnkit.engine

/** Upper bound of simulation steps per rendered frame; prevents a "spiral of death" after hitches (ADR-0004). */
public const val MAX_TICKS_PER_FRAME: Int = 5

/** Nanoseconds per simulation tick. */
public const val TICK_NANOS: Long = 1_000_000_000L / TICK_RATE

/**
 * Converts frame timestamps (e.g. from Compose `withFrameNanos`) into a number of fixed simulation steps.
 * Lives outside the simulation: wall-clock time decides *how many* ticks run, never *what* a tick does.
 */
public class FixedStepClock(
    /** Maximum ticks returned by one [advance] call; excess time is dropped. */
    public val maxTicksPerFrame: Int = MAX_TICKS_PER_FRAME,
) {
    private var lastFrameNanos: Long = -1L
    private var accumulatorNanos: Long = 0L

    /** Fraction of a tick elapsed since the last simulated tick, in `[0, 1)`; use it to interpolate rendering. */
    public val alpha: Double get() = accumulatorNanos.toDouble() / TICK_NANOS

    /** Registers a frame at [frameNanos] and returns how many ticks to simulate. The first frame returns 0. */
    public fun advance(frameNanos: Long): Int {
        if (lastFrameNanos < 0L) {
            lastFrameNanos = frameNanos
            return 0
        }
        val delta = (frameNanos - lastFrameNanos).coerceAtLeast(0L)
        lastFrameNanos = frameNanos
        accumulatorNanos += delta
        val due = accumulatorNanos / TICK_NANOS
        return if (due > maxTicksPerFrame) {
            accumulatorNanos = 0L
            maxTicksPerFrame
        } else {
            accumulatorNanos -= due * TICK_NANOS
            due.toInt()
        }
    }

    /** Forgets the previous frame, e.g. after pausing, so the next [advance] returns 0. */
    public fun reset() {
        lastFrameNanos = -1L
        accumulatorNanos = 0L
    }
}
