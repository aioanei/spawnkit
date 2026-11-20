package dev.spawnkit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class SceneTemplateTest {
    private class Health(
        var points: Long,
    )

    private object HealthCodec : StateCodec<Health> {
        override fun capture(
            value: Health,
            context: StateCaptureContext,
        ): StateValue = StateValue.Integer(value.points)

        override fun restore(
            state: StateValue,
            context: StateRestoreContext,
        ): Health = Health(StateCodecs.long.restore(state, context))
    }

    private class Fixture {
        val builder = RuntimeBuilder()
        val health = builder.component("test:health", AuthoringInfo("Health"), HealthCodec)
        val label = builder.component("test:label", AuthoringInfo("Label"), StateCodecs.string)
        val enemy =
            SceneTemplate(
                "enemy",
                listOf(
                    TemplateObject("body", components = listOf(ComponentSeed(health) { Health(3) }, ComponentSeed(label) { "Goblin" })),
                    TemplateObject("sword", parent = "body", components = listOf(ComponentSeed(label) { "Sword" })),
                    TemplateObject("shield", parent = "body"),
                ),
            )
        var spawner: (World, WorldCommands) -> Unit = { _, _ -> }

        init {
            builder.system("test:spawner") {
                object : CheckpointSystem {
                    override fun update(
                        world: World,
                        commands: WorldCommands,
                    ) = spawner(world, commands)

                    override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

                    override fun restoreState(
                        state: StateValue,
                        context: StateRestoreContext,
                    ) {}
                }
            }
        }

        val runtime = builder.build()
    }

    @Test
    fun templatesAreValidated() {
        assertFailsWith<IllegalArgumentException> { SceneTemplate("dup", listOf(TemplateObject("a"), TemplateObject("a"))) }
        assertFailsWith<IllegalArgumentException> { SceneTemplate("orphan", listOf(TemplateObject("a", parent = "missing"))) }
        assertFailsWith<IllegalArgumentException> {
            SceneTemplate("cycle", listOf(TemplateObject("a", parent = "b"), TemplateObject("b", parent = "a")))
        }
        assertFailsWith<IllegalArgumentException> { SceneTemplate("self", listOf(TemplateObject("a", parent = "a"))) }
        val fixture = Fixture()
        val foreign = Fixture()
        val world = fixture.runtime.createWorld()
        assertFailsWith<IllegalArgumentException> { world.instantiate(foreign.enemy) }
        assertEquals(0, world.entityCount, "A rejected template creates nothing")
    }

    @Test
    fun instancesAreIndependentAndKeepIdentityAndHierarchy() {
        val fixture = Fixture()
        val world = fixture.runtime.createWorld()
        val arena = world.spawn()
        val first = world.instantiate(fixture.enemy, parent = arena)
        val second = world.instantiate(fixture.enemy)
        val body1 = checkNotNull(first.entity("body"))
        val body2 = checkNotNull(second.entity("body"))
        assertEquals(listOf(body1), world.children(arena))
        assertEquals(listOf(first.entity("sword"), first.entity("shield")), world.children(body1))
        assertNull(world.parent(body2))
        assertEquals(listOf(body2), second.roots)
        assertEquals(ObjectIdentity("enemy", 1, "body"), world.identity(body1))
        assertEquals(ObjectIdentity("enemy", 2, "sword"), world.identity(checkNotNull(second.entity("sword"))))
        assertNull(world.identity(arena))
        assertNotSame(world.get(body1, fixture.health), world.get(body2, fixture.health))
        checkNotNull(world.get(body1, fixture.health)).points = 1
        assertEquals(3L, world.get(body2, fixture.health)?.points)
        assertNull(first.entity("missing"))
        world.destroy(body2)
        val reused = world.spawn()
        assertNull(world.identity(reused), "Destroy clears identity before a slot is reused")
    }

    @Test
    fun commandsInstantiateAtCommitAndCheckpointsReplayIdentity() {
        val fixture = Fixture()
        val created = mutableListOf<Long>()
        fixture.spawner = { world, commands ->
            if (world.tick % 2L == 1L) commands.instantiate(fixture.enemy) { created += it.number }
            for (entity in world.entities(fixture.health)) checkNotNull(world.get(entity, fixture.health)).points += 1
        }
        val world = fixture.runtime.createWorld()
        repeat(3) { world.step() }
        assertEquals(listOf(1L, 2L), created)
        val snapshot = world.snapshot()
        repeat(4) { world.step() }
        val restored = fixture.runtime.restore(snapshot)
        repeat(4) { restored.step() }
        assertEquals(world.stateHash(), restored.stateHash())
        val identities = restored.entities().mapNotNull { restored.identity(it) }
        assertEquals(listOf(1L, 2L, 3L, 4L), identities.map { it.instance }.distinct())
        assertEquals(GOLDEN_HASH, world.stateHash())
    }

    /** Recorded on JVM; every target must reproduce it. */
    private companion object {
        const val GOLDEN_HASH = -1755506672648123874L
    }
}
