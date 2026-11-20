package dev.spawnkit.samples.runtimeprobe

import dev.spawnkit.runtime.RuntimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CheckpointReplayTest {
    @Test
    fun midRunRestoreIncludesPrivateSystemStateAndMatchesUninterruptedExecution() {
        val builder = RuntimeBuilder()
        val feature = CounterFeature(builder)
        val runtime = builder.build()
        val uninterrupted = runtime.createWorld()
        val original = uninterrupted.spawn()
        uninterrupted.set(original, feature.counter, Counter(5))
        repeat(2) { uninterrupted.step() }
        val snapshot = uninterrupted.snapshot()
        repeat(3) { uninterrupted.step() }
        val resumed = runtime.restore(snapshot)
        assertFalse(resumed.isAlive(original))
        repeat(3) { resumed.step() }
        assertEquals(Counter(20), resumed.get(resumed.entities(feature.counter).single(), feature.counter))
        assertEquals(uninterrupted.tick, resumed.tick)
        assertEquals(uninterrupted.stateHash(), resumed.stateHash())
        val again = runtime.restore(snapshot)
        repeat(3) { again.step() }
        assertEquals(resumed.stateHash(), again.stateHash())
    }

    /**
     * Recorded from JVM after six ticks starting at Counter(5), using checkpoint hash v1 and private updates=6.
     * Every target must use this same golden; a target-only mismatch is a bug, not a reason for another golden.
     */
    @Test
    fun externalConsumerStateMatchesCrossTargetGolden() {
        val builder = RuntimeBuilder()
        val feature = CounterFeature(builder)
        val world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, feature.counter, Counter(5))
        repeat(6) { world.step() }
        assertEquals(Counter(26), world.get(entity, feature.counter))
        assertEquals(GOLDEN_HASH, world.stateHash())
    }

    private companion object {
        const val GOLDEN_HASH: Long = -4909871966990312915L
    }
}
