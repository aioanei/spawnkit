package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class WorldSnapshotTest {
    @Test
    fun emptyWorldRoundTripsAndSnapshotsBelongToTheirRuntime() {
        val runtime = RuntimeBuilder().build()
        val world = runtime.createWorld()
        world.step()
        val snapshot = world.snapshot()
        val restored = runtime.restore(snapshot)
        assertNotSame(world, restored)
        assertEquals(1L, restored.tick)
        assertEquals(0, restored.entityCount)
        assertEquals(snapshot.stateHash(), restored.stateHash())
        assertFailsWith<IllegalArgumentException> { RuntimeBuilder().build().restore(snapshot) }
    }

    @Test
    fun allocatorHistoryAndFreeListOrderAreRestored() {
        val runtime = RuntimeBuilder().build()
        val world = runtime.createWorld()
        val first = world.spawn()
        val second = world.spawn()
        world.spawn()
        world.destroy(first)
        world.destroy(second)
        val snapshot = world.snapshot()
        val restored = runtime.restore(snapshot)
        repeat(3) {
            val originalNext = world.spawn()
            val restoredNext = restored.spawn()
            assertEquals(originalNext.index, restoredNext.index)
            assertEquals(originalNext.generation, restoredNext.generation)
        }
        assertEquals(world.stateHash(), restored.stateHash())
        assertFalse(restored.isAlive(first))
    }

    @Test
    fun snapshotAndRepeatedRestoresDoNotAliasMutableValuesOrCodecBuffers() {
        val encoded = mutableListOf<StateValue>()
        val codec =
            object : StateCodec<MutableList<Int>> {
                override fun capture(
                    value: MutableList<Int>,
                    context: StateCaptureContext,
                ): StateValue {
                    encoded.clear()
                    encoded += value.map { StateValue.Integer(it.toLong()) }
                    return StateValue.Sequence(encoded)
                }

                override fun restore(
                    state: StateValue,
                    context: StateRestoreContext,
                ): MutableList<Int> = (state as StateValue.Sequence).values.map { (it as StateValue.Integer).value.toInt() }.toMutableList()
            }
        val builder = RuntimeBuilder()
        val numbers = builder.component("test:numbers", AuthoringInfo("Numbers"), codec)
        val runtime = builder.build()
        val world = runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, numbers, mutableListOf(1, 2))
        val snapshot = world.snapshot()
        val expectedHash = snapshot.stateHash()
        encoded[0] = StateValue.Integer(999)
        world.get(entity, numbers)!!.add(3)
        val first = runtime.restore(snapshot)
        val firstNumbers = first.get(first.entities().single(), numbers)!!
        assertEquals(listOf(1, 2), firstNumbers)
        firstNumbers.add(4)
        val second = runtime.restore(snapshot)
        assertEquals(listOf(1, 2), checkNotNull(second.get(second.entities().single(), numbers)).toList())
        assertEquals(expectedHash, second.stateHash())
        assertEquals(expectedHash, snapshot.stateHash())
    }

    @Test
    fun liveAndStaleEntityReferencesAreRemappedIntoTheNewWorld() {
        val codec =
            object : StateCodec<List<EntityHandle>> {
                override fun capture(
                    value: List<EntityHandle>,
                    context: StateCaptureContext,
                ): StateValue = StateValue.Sequence(value.map(context::entity))

                override fun restore(
                    state: StateValue,
                    context: StateRestoreContext,
                ): List<EntityHandle> = (state as StateValue.Sequence).values.map(context::entity)
            }
        val builder = RuntimeBuilder()
        val links = builder.component("test:links", AuthoringInfo("Links"), codec)
        val runtime = builder.build()
        val world = runtime.createWorld()
        val owner = world.spawn()
        val live = world.spawn()
        val stale = world.spawn()
        world.destroy(stale)
        world.set(owner, links, listOf(live, stale))
        val restored = runtime.restore(world.snapshot())
        val restoredLinks = restored.get(restored.entities(links).single(), links)!!
        assertTrue(restored.isAlive(restoredLinks[0]))
        assertFalse(world.isAlive(restoredLinks[0]))
        assertFalse(restored.isAlive(restoredLinks[1]))
        assertEquals(stale.generation, restoredLinks[1].generation)
        assertEquals(world.stateHash(), restored.stateHash())
    }

    @Test
    fun foreignEntityReferencesAreRejectedAtCapture() {
        val foreign = RuntimeBuilder().build().createWorld().spawn()
        val builder = RuntimeBuilder()
        val links =
            builder.component(
                "test:link",
                AuthoringInfo("Link"),
                object : StateCodec<EntityHandle> {
                    override fun capture(
                        value: EntityHandle,
                        context: StateCaptureContext,
                    ): StateValue = context.entity(value)

                    override fun restore(
                        state: StateValue,
                        context: StateRestoreContext,
                    ): EntityHandle = context.entity(state)
                },
            )
        val world = builder.build().createWorld()
        world.set(world.spawn(), links, foreign)
        assertFailsWith<IllegalArgumentException> { world.snapshot() }
        assertFalse(world.isFaulted)
    }

    @Test
    fun missingComponentOrPrivateSystemStateContractsFailExplicitly() {
        val builder = RuntimeBuilder()
        val unknown = builder.component<Int>("test:unknown", AuthoringInfo("Unknown"))
        val world = builder.build().createWorld()
        world.snapshot() // An unused registration does not lose any current state.
        world.set(world.spawn(), unknown, 7)
        assertTrue(assertFailsWith<IllegalStateException> { world.snapshot() }.message.orEmpty().contains("test:unknown"))
        val systems = RuntimeBuilder()
        systems.system("test:private") { WorldSystem { _, _ -> } }
        val withUnknownSystem = systems.build().createWorld()
        assertTrue(assertFailsWith<IllegalStateException> { withUnknownSystem.snapshot() }.message.orEmpty().contains("test:private"))
    }

    @Test
    fun captureIsIdleOnlyAndBlocksWritesThroughTheCapturedWorldApi() {
        val builder = RuntimeBuilder()
        lateinit var world: World
        lateinit var component: ComponentType<Int>
        val codec =
            object : StateCodec<Int> {
                override fun capture(
                    value: Int,
                    context: StateCaptureContext,
                ): StateValue {
                    assertFailsWith<IllegalStateException> { world.step() }
                    assertFailsWith<IllegalStateException> { world.spawn() }
                    assertFailsWith<IllegalStateException> { world.set(world.entities().single(), component, 99) }
                    return StateValue.Integer(value.toLong())
                }

                override fun restore(
                    state: StateValue,
                    context: StateRestoreContext,
                ): Int = (state as StateValue.Integer).value.toInt()
            }
        component = builder.component("test:value", AuthoringInfo("Value"), codec)
        world = builder.build().createWorld()
        val entity = world.spawn()
        world.set(entity, component, 3)
        world.snapshot()
        assertEquals(3, world.get(entity, component))
        world.step()
    }

    @Test
    fun failedCaptureDoesNotFaultTheSourceOrPreventAnotherCapture() {
        var fail = true
        val codec =
            object : StateCodec<Int> {
                override fun capture(
                    value: Int,
                    context: StateCaptureContext,
                ): StateValue {
                    check(!fail) { "capture failed" }
                    return StateValue.Integer(value.toLong())
                }

                override fun restore(
                    state: StateValue,
                    context: StateRestoreContext,
                ): Int = (state as StateValue.Integer).value.toInt()
            }
        val builder = RuntimeBuilder()
        val value = builder.component("test:value", AuthoringInfo("Value"), codec)
        val world = builder.build().createWorld()
        world.set(world.spawn(), value, 5)
        assertFailsWith<IllegalStateException> { world.snapshot() }
        assertFalse(world.isFaulted)
        fail = false
        world.snapshot()
        world.step()
        assertEquals(1L, world.tick)
    }

    @Test
    fun hashingIsCanonicalButIncludesValuesAllocationAndFloatingPointBits() {
        fun hash(
            reverse: Boolean,
            number: Double,
        ): Long {
            val builder = RuntimeBuilder()
            val value =
                builder.component(
                    "test:value",
                    AuthoringInfo("Value"),
                    object : StateCodec<Double> {
                        override fun capture(
                            value: Double,
                            context: StateCaptureContext,
                        ): StateValue {
                            val fields =
                                if (reverse) {
                                    linkedMapOf("z" to StateValue.Text("é🦈"), "a" to StateValue.Decimal(value))
                                } else {
                                    linkedMapOf("a" to StateValue.Decimal(value), "z" to StateValue.Text("é🦈"))
                                }
                            return StateValue.Record(fields)
                        }

                        override fun restore(
                            state: StateValue,
                            context: StateRestoreContext,
                        ): Double = error("not needed")
                    },
                )
            val world = builder.build().createWorld()
            world.set(world.spawn(), value, number)
            return world.stateHash()
        }
        assertEquals(hash(false, 1.0), hash(true, 1.0))
        assertNotEquals(hash(false, 0.0), hash(false, -0.0))
        assertNotEquals(hash(false, 1.0), hash(false, 2.0))
        val runtime = RuntimeBuilder().build()
        val untouched = runtime.createWorld()
        val recycled = runtime.createWorld()
        recycled.destroy(recycled.spawn())
        assertNotEquals(untouched.stateHash(), recycled.stateHash())
    }

    @Test
    fun cyclicOrOverBudgetStateIsRejected() {
        val values = mutableListOf<StateValue>()
        val cycle = StateValue.Sequence(values)
        values += cycle
        val builder = RuntimeBuilder()
        val component =
            builder.component(
                "test:cycle",
                AuthoringInfo("Cycle"),
                object : StateCodec<Int> {
                    override fun capture(
                        value: Int,
                        context: StateCaptureContext,
                    ): StateValue = cycle

                    override fun restore(
                        state: StateValue,
                        context: StateRestoreContext,
                    ): Int = 0
                },
            )
        val world = builder.build().createWorld()
        world.set(world.spawn(), component, 1)
        assertFailsWith<IllegalArgumentException> { world.snapshot(SnapshotLimits(maxDepth = 8)) }
        assertFalse(world.isFaulted)
    }

    @Test
    fun restoringFailureDoesNotConsumeSnapshotOrChangeSource() {
        var fail = true
        val builder = RuntimeBuilder()
        val value =
            builder.component(
                "test:value",
                AuthoringInfo("Value"),
                object : StateCodec<Int> {
                    override fun capture(
                        value: Int,
                        context: StateCaptureContext,
                    ): StateValue = StateValue.Integer(value.toLong())

                    override fun restore(
                        state: StateValue,
                        context: StateRestoreContext,
                    ): Int {
                        check(!fail) { "decode failed" }
                        return (state as StateValue.Integer).value.toInt()
                    }
                },
            )
        val runtime = builder.build()
        val source = runtime.createWorld()
        val entity = source.spawn()
        source.set(entity, value, 7)
        val snapshot = source.snapshot()
        val hash = snapshot.stateHash()
        assertFailsWith<IllegalStateException> { runtime.restore(snapshot) }
        assertEquals(7, source.get(entity, value))
        assertFalse(source.isFaulted)
        assertEquals(hash, snapshot.stateHash())
        fail = false
        assertEquals(hash, runtime.restore(snapshot).stateHash())
    }

    @Test
    fun entityReferenceContextsExpireAfterTheirOperation() {
        var capture: StateCaptureContext? = null
        var restore: StateRestoreContext? = null
        var restoredState: StateValue? = null
        val builder = RuntimeBuilder()
        val link =
            builder.component(
                "test:link",
                AuthoringInfo("Link"),
                object : StateCodec<EntityHandle> {
                    override fun capture(
                        value: EntityHandle,
                        context: StateCaptureContext,
                    ): StateValue {
                        capture = context
                        return context.entity(value)
                    }

                    override fun restore(
                        state: StateValue,
                        context: StateRestoreContext,
                    ): EntityHandle {
                        restore = context
                        restoredState = state
                        return context.entity(state)
                    }
                },
            )
        val runtime = builder.build()
        val world = runtime.createWorld()
        val entity = world.spawn()
        world.set(entity, link, entity)
        val snapshot = world.snapshot()
        assertFailsWith<IllegalStateException> { capture!!.entity(entity) }
        val restored = runtime.restore(snapshot)
        assertTrue(restored.isAlive(restored.get(restored.entities().single(), link)!!))
        assertFailsWith<IllegalStateException> { restore!!.entity(restoredState!!) }
    }

    @Test
    fun updateCommitAndFaultedWorldsCannotBeCaptured() {
        val builder = RuntimeBuilder()
        builder.system("test:boundary") {
            WorldSystem { world, commands ->
                assertFailsWith<IllegalStateException> { world.snapshot() }
                commands.spawn { assertFailsWith<IllegalStateException> { snapshot() } }
            }
        }
        val world = builder.build().createWorld()
        world.step()
        assertFalse(world.isFaulted)
        val failing = RuntimeBuilder()
        failing.system("test:failure") { WorldSystem { _, _ -> error("failed tick") } }
        val faulted = failing.build().createWorld()
        assertFailsWith<IllegalStateException> { faulted.step() }
        assertFailsWith<IllegalStateException> { faulted.snapshot() }
    }

    @Test
    fun valueBudgetCoversTheWholeSnapshotAndLimitsAreValidated() {
        val builder = RuntimeBuilder()
        val number = builder.component("test:number", AuthoringInfo("Number"), StateCodecs.int)
        val world = builder.build().createWorld()
        world.set(world.spawn(), number, 1)
        world.set(world.spawn(), number, 2)
        assertFailsWith<IllegalArgumentException> { world.snapshot(SnapshotLimits(maxValues = 1)) }
        world.snapshot(SnapshotLimits(maxValues = 2))
        assertFailsWith<IllegalArgumentException> { SnapshotLimits(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { SnapshotLimits(maxValues = 0) }
    }

    @Test
    fun registrationAndAttachmentOrderDoNotChangeCanonicalHash() {
        fun hash(reverse: Boolean): Long {
            val builder = RuntimeBuilder()
            val first = builder.component(if (reverse) "test:b" else "test:a", AuthoringInfo("First"), StateCodecs.int)
            val second = builder.component(if (reverse) "test:a" else "test:b", AuthoringInfo("Second"), StateCodecs.int)
            val world = builder.build().createWorld()
            val entity = world.spawn()
            world.set(entity, first, if (reverse) 2 else 1)
            world.set(entity, second, if (reverse) 1 else 2)
            return world.stateHash()
        }
        assertEquals(hash(false), hash(true))
    }
}
