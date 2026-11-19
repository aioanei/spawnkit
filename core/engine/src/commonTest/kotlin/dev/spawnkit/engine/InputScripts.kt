package dev.spawnkit.engine

/** Deterministic input sequences used by determinism and regression tests. */
internal object InputScripts {
    /**
     * 960 ticks (16 s) running right through Meadow while hopping: every 45 ticks the jump button is held for 18 ticks,
     * plus a few pauses and short left steps so stopping, turning and short hops are exercised too.
     */
    val hopRightMeadow: List<InputFrame> =
        buildList {
            repeat(960) { tick ->
                val phase = tick % 45
                val frame =
                    when {
                        tick % 300 in 280..289 -> InputFrame.NONE
                        tick % 300 in 290..299 -> InputFrame.of(InputAction.Left)
                        phase < 18 -> InputFrame.of(InputAction.Right, InputAction.Jump)
                        else -> InputFrame.of(InputAction.Right)
                    }
                add(frame)
            }
        }
}
