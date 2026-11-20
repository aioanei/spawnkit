package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputTest {
    private class Position(
        var x: Long = 0,
        var jumps: Long = 0,
    )

    private object PositionCodec : StateCodec<Position> {
        override fun capture(
            value: Position,
            context: StateCaptureContext,
        ): StateValue = StateValue.Sequence(listOf(StateValue.Integer(value.x), StateValue.Integer(value.jumps)))

        override fun restore(
            state: StateValue,
            context: StateRestoreContext,
        ): Position {
            val (x, jumps) = (state as StateValue.Sequence).values
            return Position(StateCodecs.long.restore(x, context), StateCodecs.long.restore(jumps, context))
        }
    }

    private class Fixture {
        val builder = RuntimeBuilder()
        val jump = builder.action("test:jump", AuthoringInfo("Jump"))
        val moveX = builder.axis("test:move-x", AuthoringInfo("Move left/right"))
        val position = builder.component("test:position", AuthoringInfo("Position"), PositionCodec)
        val observed = mutableListOf<String>()

        init {
            builder.system("test:control") {
                object : CheckpointSystem {
                    override fun update(
                        world: World,
                        commands: WorldCommands,
                    ) {
                        val input = world.input
                        if (input.wasPressed(jump)) observed += "pressed@${world.tick}"
                        if (input.wasReleased(jump)) observed += "released@${world.tick}"
                        for (entity in world.entities(position)) {
                            val p = checkNotNull(world.get(entity, position))
                            p.x += (input.axis(moveX) * 4).toLong()
                            if (input.wasPressed(jump)) p.jumps++
                        }
                    }

                    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

                    override fun restoreState(
                        state: StateValue,
                        context: StateRestoreContext,
                    ) {}
                }
            }
        }

        val runtime = builder.build()

        fun world(): World = runtime.createWorld().also { it.set(it.spawn(), position, Position()) }
    }

    @Test
    fun edgesAreRelativeToThePreviousTickAndHeldInputCarriesOver() {
        val fixture = Fixture()
        val world = fixture.world()
        world.submitInput(InputFrame(down = setOf(fixture.jump), axes = mapOf(fixture.moveX to 1.0)))
        world.step()
        world.step() // no new frame: still held, no new press
        world.submitInput(InputFrame())
        world.step()
        world.submitInput(InputFrame(down = setOf(fixture.jump)))
        world.step()
        assertEquals(listOf("pressed@1", "released@3", "pressed@4"), fixture.observed)
        val p = checkNotNull(world.get(world.entities().single(), fixture.position))
        assertEquals(8L, p.x, "Axis 1.0 applied on ticks 1 and 2 only")
        assertEquals(2L, p.jumps)
        assertTrue(world.input.isDown(fixture.jump))
        assertEquals(0.0, world.input.axis(fixture.moveX))
        assertEquals(listOf("test:jump"), fixture.runtime.inputActions.map { it.id })
        assertEquals(listOf("test:move-x"), fixture.runtime.inputAxes.map { it.id })
    }

    @Test
    fun invalidAndForeignInputIsRejected() {
        val fixture = Fixture()
        val world = fixture.world()
        val other = Fixture()
        assertFailsWith<IllegalArgumentException> { world.submitInput(InputFrame(axes = mapOf(fixture.moveX to 1.5))) }
        assertFailsWith<IllegalArgumentException> { world.submitInput(InputFrame(axes = mapOf(fixture.moveX to Double.NaN))) }
        assertFailsWith<IllegalArgumentException> { world.submitInput(InputFrame(down = setOf(other.jump))) }
        assertFailsWith<IllegalArgumentException> {
            world.submitInput(InputFrame(pointers = listOf(Pointer(1, 0.0, 0.0, true), Pointer(1, 2.0, 2.0, false))))
        }
        assertFailsWith<IllegalArgumentException> { world.submitInput(InputFrame(pointers = listOf(Pointer(1, Double.NaN, 0.0, true)))) }
        assertFailsWith<IllegalArgumentException> { world.input.isDown(other.jump) }
        world.submitInput(InputFrame(pointers = listOf(Pointer(7, 10.0, 20.0, true))))
        world.step()
        assertEquals(listOf(Pointer(7, 10.0, 20.0, true)), world.input.pointers)
        assertFailsWith<IllegalArgumentException> {
            RuntimeBuilder().apply {
                action("test:a", AuthoringInfo("A"))
                action("test:a", AuthoringInfo("A"))
            }
        }
    }

    @Test
    fun checkpointsCaptureInputAndRecordedFramesReplayExactly() {
        val fixture = Fixture()
        val recording =
            List(12) { tick ->
                InputFrame(
                    down = if (tick % 4 < 2) setOf(fixture.jump) else emptySet(),
                    axes = mapOf(fixture.moveX to (tick % 3 - 1).toDouble()),
                )
            }
        val live = fixture.world()
        for ((tick, frame) in recording.withIndex()) {
            live.submitInput(frame)
            live.step()
            if (tick == 5) {
                // Capture between ticks, with a pending frame for the next one.
                live.submitInput(recording[6])
            }
        }
        val replay = fixture.world()
        recording.forEach {
            replay.submitInput(it)
            replay.step()
        }
        assertEquals(live.stateHash(), replay.stateHash())

        val partial = fixture.world()
        recording.take(6).forEach {
            partial.submitInput(it)
            partial.step()
        }
        partial.submitInput(recording[6])
        val snapshot = partial.snapshot()
        val restored = fixture.runtime.restore(snapshot)
        restored.step() // uses the pending frame captured in the snapshot
        recording.drop(7).forEach {
            restored.submitInput(it)
            restored.step()
        }
        assertEquals(live.stateHash(), restored.stateHash())
        assertEquals(GOLDEN_HASH, live.stateHash())
    }

    @Test
    fun inputCannotBeSubmittedDuringATick() {
        val builder = RuntimeBuilder()
        val jump = builder.action("test:jump", AuthoringInfo("Jump"))
        var attempted = false
        builder.system("test:sneaky") {
            WorldSystem { world, _ ->
                attempted = true
                assertFailsWith<IllegalStateException> { world.submitInput(InputFrame(down = setOf(jump))) }
            }
        }
        val world = builder.build().createWorld()
        world.step()
        assertTrue(attempted)
        assertFalse(world.input.isDown(jump))
    }

    @Test
    fun submittedFramesAreIsolatedFromLaterCallerChanges() {
        val fixture = Fixture()
        val world = fixture.world()
        val held = mutableSetOf(fixture.jump)
        val axes = mutableMapOf(fixture.moveX to 0.5)
        world.submitInput(InputFrame(down = held, axes = axes))
        held.clear()
        axes[fixture.moveX] = 5.0
        world.step()
        assertTrue(world.input.isDown(fixture.jump))
        assertEquals(0.5, world.input.axis(fixture.moveX))
    }

    @Test
    fun pointerOnlyInputIsCheckpointedWithoutRegisteredActions() {
        val builder = RuntimeBuilder()
        val taps = mutableListOf<Int>()
        builder.system("test:taps") {
            object : CheckpointSystem {
                override fun update(
                    world: World,
                    commands: WorldCommands,
                ) {
                    taps += world.input.pointers.size
                }

                override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

                override fun restoreState(
                    state: StateValue,
                    context: StateRestoreContext,
                ) {}
            }
        }
        val runtime = builder.build()
        val world = runtime.createWorld()
        val before = world.stateHash()
        world.submitInput(InputFrame(pointers = listOf(Pointer(1, 3.0, 4.0, true))))
        assertTrue(before != world.stateHash(), "Pending pointers are part of the checkpoint")
        val restored = runtime.restore(world.snapshot())
        restored.step()
        assertEquals(listOf(Pointer(1, 3.0, 4.0, true)), restored.input.pointers)
        assertEquals(listOf(1), taps)
    }

    @Test
    fun previousPointersLetRulesDetectPressesAndSurviveCheckpoints() {
        val builder = RuntimeBuilder()
        val seen = mutableListOf<Pair<List<Pointer>, List<Pointer>>>()
        builder.system("test:presses") {
            object : CheckpointSystem {
                override fun update(
                    world: World,
                    commands: WorldCommands,
                ) {
                    seen += world.input.previousPointers to world.input.pointers
                }

                override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

                override fun restoreState(
                    state: StateValue,
                    context: StateRestoreContext,
                ) {}
            }
        }
        val runtime = builder.build()
        val world = runtime.createWorld()
        val press = listOf(Pointer(1, 3.0, 4.0, true))
        world.submitInput(InputFrame(pointers = press))
        world.step()
        val snapshot = world.snapshot()
        world.submitInput(InputFrame())
        world.step()
        assertEquals(listOf(emptyList<Pointer>() to press, press to emptyList()), seen)
        val restored = runtime.restore(snapshot)
        assertEquals(press, restored.input.pointers)
        restored.submitInput(InputFrame())
        restored.step()
        assertEquals(press to emptyList(), seen.last(), "The previous frame is restored from the checkpoint")
    }

    /** Recorded on JVM; every target must reproduce it. */
    private companion object {
        const val GOLDEN_HASH = -8903530279068901628L
    }
}
