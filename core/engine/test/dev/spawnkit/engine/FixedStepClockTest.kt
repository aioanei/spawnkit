package dev.spawnkit.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class FixedStepClockTest {
    @Test
    fun firstFrameYieldsZeroTicks() {
        assertEquals(0, FixedStepClock().advance(123_456_789L))
    }

    @Test
    fun sixtyHzFramesYieldOneTickEach() {
        val clock = FixedStepClock()
        clock.advance(0L)
        val ticks = (1..60).map { clock.advance(it * 16_666_667L) }
        assertEquals(List(60) { 1 }, ticks)
    }

    @Test
    fun hundredTwentyHzFramesYieldATickEveryOtherFrame() {
        val clock = FixedStepClock()
        clock.advance(0L)
        val total = (1..120).sumOf { clock.advance(it * 8_333_334L) }
        assertEquals(60, total)
    }

    @Test
    fun clampsLargeDelta() {
        val clock = FixedStepClock()
        clock.advance(0L)
        assertEquals(MAX_TICKS_PER_FRAME, clock.advance(5_000_000_000L))
        assertEquals(0.0, clock.alpha)
        assertEquals(1, clock.advance(5_000_000_000L + TICK_NANOS))
    }

    @Test
    fun timeGoingBackwardsYieldsNoTicks() {
        val clock = FixedStepClock()
        clock.advance(1_000_000_000L)
        assertEquals(0, clock.advance(900_000_000L))
    }

    @Test
    fun resetForgetsPreviousFrame() {
        val clock = FixedStepClock()
        clock.advance(0L)
        clock.reset()
        assertEquals(0, clock.advance(10_000_000_000L))
    }
}
