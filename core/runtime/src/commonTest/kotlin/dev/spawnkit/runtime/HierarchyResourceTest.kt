package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyResourceTest {
    private class Score(
        var points: Long = 0,
    )

    private object ScoreCodec : StateCodec<Score> {
        override fun capture(
            value: Score,
            context: StateCaptureContext,
        ): StateValue = StateValue.Integer(value.points)

        override fun restore(
            state: StateValue,
            context: StateRestoreContext,
        ): Score = Score(StateCodecs.long.restore(state, context))
    }

    @Test
    fun childrenKeepOrderAndCanBeInsertedReorderedAndReparented() {
        val world = RuntimeBuilder().build().createWorld()
        val root = world.spawn()
        val (a, b, c) = List(3) { world.spawn() }
        world.setParent(a, root)
        world.setParent(b, root)
        world.setParent(c, root, index = 0)
        assertEquals(listOf(c, a, b), world.children(root))
        assertEquals(root, world.parent(a))
        world.setParent(b, root, index = 0)
        assertEquals(listOf(b, c, a), world.children(root), "Reparenting under the same parent reorders")
        world.setParent(a, c)
        assertEquals(listOf(b, c), world.children(root))
        assertEquals(listOf(a), world.children(c))
        world.setParent(a, null)
        assertNull(world.parent(a))
        assertEquals(emptyList(), world.children(c))
        assertFailsWith<IllegalArgumentException> { world.setParent(a, root, index = 5) }
        assertNull(world.parent(a), "A rejected insertion leaves the object a root")
    }

    @Test
    fun cyclesSelfParentingAndStaleOrForeignHandlesAreRejected() {
        val world = RuntimeBuilder().build().createWorld()
        val (top, middle, leaf) = List(3) { world.spawn() }
        world.setParent(middle, top)
        world.setParent(leaf, middle)
        assertFailsWith<IllegalArgumentException> { world.setParent(top, leaf) }
        assertFailsWith<IllegalArgumentException> { world.setParent(top, top) }
        assertEquals(listOf(middle), world.children(top), "Rejected edits change nothing")
        val other = RuntimeBuilder().build().createWorld().spawn()
        assertFailsWith<IllegalArgumentException> { world.setParent(leaf, other) }
        val gone = world.spawn()
        world.destroy(gone)
        assertFailsWith<IllegalArgumentException> { world.setParent(gone, top) }
        assertFailsWith<IllegalArgumentException> { world.children(gone) }
    }

    @Test
    fun destroyDetachesChildrenAndDestroyTreeRemovesDescendants() {
        val world = RuntimeBuilder().build().createWorld()
        val (root, a, b, a1) = List(4) { world.spawn() }
        world.setParent(a, root)
        world.setParent(b, root)
        world.setParent(a1, a)
        world.destroy(a)
        assertEquals(listOf(b), world.children(root))
        assertNull(world.parent(a1), "Children of a destroyed object become roots")
        val reused = world.spawn()
        assertEquals(a.index, reused.index)
        assertNull(world.parent(reused))
        assertEquals(emptyList(), world.children(reused), "A reused slot starts without hierarchy")
        world.setParent(a1, b)
        world.destroyTree(root)
        assertFalse(world.isAlive(root) || world.isAlive(b) || world.isAlive(a1))
        assertTrue(world.isAlive(reused))
        assertEquals(1, world.entityCount)
    }

    @Test
    fun hierarchyCommandsApplyAtCommitAndSkipDestroyedTargets() {
        val builder = RuntimeBuilder()
        var plan: (World, WorldCommands) -> Unit = { _, _ -> }
        builder.system("test:plan") { WorldSystem { world, commands -> plan(world, commands) } }
        val world = builder.build().createWorld()
        val (parent, child, doomed) = List(3) { world.spawn() }
        plan = { w, commands ->
            assertFailsWith<IllegalStateException> { w.setParent(child, parent) }
            commands.setParent(child, parent)
            commands.destroy(doomed)
            commands.setParent(doomed, parent)
            assertNull(w.parent(child), "Recorded hierarchy edits wait for commit")
        }
        world.step()
        assertEquals(listOf(child), world.children(parent))
        plan = { _, commands -> commands.destroyTree(parent) }
        world.step()
        assertFalse(world.isAlive(parent) || world.isAlive(child))
    }

    @Test
    fun resourcesAreFreshPerWorldAndCheckpointedWithHierarchy() {
        val builder = RuntimeBuilder()
        val score = builder.resource("test:score", AuthoringInfo("Score"), ScoreCodec) { Score() }
        val random = builder.resource("test:random", AuthoringInfo("Random"), SeededRandom.codec) { SeededRandom(7) }
        builder.system("test:roll") {
            object : CheckpointSystem {
                override fun update(
                    world: World,
                    commands: WorldCommands,
                ) {
                    world.resource(score).points += world.resource(random).nextInt(6) + 1L
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
        val other = runtime.createWorld()
        assertNotSame(world.resource(score), other.resource(score))
        val (root, child) = List(2) { world.spawn() }
        world.setParent(child, root)
        repeat(3) { world.step() }
        val snapshot = world.snapshot()
        repeat(4) { world.step() }
        val restored = runtime.restore(snapshot)
        assertEquals(listOf(child.index), restored.children(restored.entities().first()).map { it.index })
        repeat(4) { restored.step() }
        assertEquals(world.resource(score).points, restored.resource(score).points)
        assertEquals(world.stateHash(), restored.stateHash())
        assertEquals(GOLDEN_POINTS, world.resource(score).points)
        assertEquals(GOLDEN_HASH, world.stateHash())
        assertFailsWith<IllegalArgumentException> { RuntimeBuilder().build().createWorld().resource(score) }
    }

    @Test
    fun snapshotsRequireCodecsForEveryResource() {
        val builder = RuntimeBuilder()
        builder.resource("test:plain", AuthoringInfo("Plain"), checkpoint = null) { Score() }
        val world = builder.build().createWorld()
        assertFailsWith<IllegalStateException> { world.snapshot() }
        assertFailsWith<IllegalArgumentException> {
            RuntimeBuilder().apply {
                resource("test:a", AuthoringInfo("A"), null) { 1 }
                resource("test:a", AuthoringInfo("A"), null) { 1 }
            }
        }
    }

    @Test
    fun seededRandomSequencesMatchTheCrossTargetGolden() {
        val random = SeededRandom(42)
        assertEquals(GOLDEN_LONGS, List(3) { random.nextLong() })
        assertEquals(GOLDEN_INTS, List(8) { random.nextInt(10) })
        val unit = random.nextDouble()
        assertTrue(unit >= 0.0 && unit < 1.0)
        assertEquals(GOLDEN_DOUBLE_BITS, unit.toRawBits())
        assertFailsWith<IllegalArgumentException> { random.nextInt(0) }
    }

    /** Recorded on JVM; every target must produce identical values. */
    private companion object {
        const val GOLDEN_POINTS = 26L
        const val GOLDEN_HASH = 27239661146528145L
        val GOLDEN_LONGS = listOf(-4767286540954276203L, 2949826092126892291L, 5139283748462763858L)
        val GOLDEN_INTS = listOf(5, 5, 7, 2, 3, 7, 4, 3)
        const val GOLDEN_DOUBLE_BITS = 4602552523571676564L
    }
}
