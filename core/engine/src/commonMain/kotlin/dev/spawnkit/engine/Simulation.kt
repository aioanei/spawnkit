package dev.spawnkit.engine

import kotlin.math.floor

/** Simulation steps per second. */
public const val TICK_RATE: Int = 60

/** Duration of one simulation step in seconds. */
public const val DT: Double = 1.0 / 60

/** Tolerance for edge comparisons, far below any visible distance. */
internal const val EPSILON: Double = 1e-9

/** How far below the scene the player may fall before dying, in tiles. */
internal const val FALL_OUT_MARGIN: Double = 2.0

/** Hazard tiles hurt only when the player overlaps them by more than this margin (forgiving spikes). */
internal const val HAZARD_MARGIN: Double = 0.15

/** A stomp counts if the player's previous bottom was at most this far below the enemy's top. */
internal const val STOMP_TOLERANCE: Double = 0.25

/** Bounce speed after a stomp, as a fraction of the jump velocity. */
internal const val STOMP_BOUNCE: Double = 0.6

/** Vertical speed multiplier applied once when the jump button is released while rising. */
internal const val JUMP_CUT: Double = 0.5

/** How far ahead of its feet a patrolling enemy probes for ground. */
internal const val LEDGE_PROBE: Double = 0.05

internal fun floorInt(value: Double): Int = floor(value).toInt()
