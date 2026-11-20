package dev.spawnkit.program

import dev.spawnkit.program.P.declare
import dev.spawnkit.program.P.each
import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.host
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.query
import dev.spawnkit.program.P.s
import dev.spawnkit.program.P.self
import dev.spawnkit.program.P.v
import dev.spawnkit.program.P.wait
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramDestroyTest {
    private val fixture = ProgramFixture()

    private fun destroy(
        target: Expr,
        id: String = "destroy",
    ) = Stmt.Destroy(target, s(id))

    /** A world with a player running [program] (it has `value` only) and two coins (they have `score`). */
    private inner class Scene(
        program: Program,
    ) {
        val world: World = fixture.runtime.createWorld()
        val player: EntityHandle = world.spawn()
        val coins: List<EntityHandle> = List(2) { world.spawn().also { world.set(it, fixture.score, 0L) } }

        init {
            world.set(player, fixture.value, 1L)
            world.set(player, fixture.feature.behavior, fixture.feature.instantiate(fixture.prepare(program)))
        }

        val instance: ProgramInstance get() = checkNotNull(world.get(player, fixture.feature.behavior))
    }

    private val collectAll =
        Program("collect", handlers = listOf(everyTick(each("coin", query("score"), listOf(destroy(v("coin")))))))

    @Test
    fun destroyRemovesObjectsAndTheirChildrenWhenTheTickEnds() {
        val scene = Scene(collectAll)
        val sparkle = scene.world.spawn()
        scene.world.setParent(sparkle, scene.coins[0])
        scene.world.step()
        assertNull(scene.instance.fault)
        assertTrue(scene.coins.none(scene.world::isAlive), "Both coins were removed")
        assertFalse(scene.world.isAlive(sparkle), "Removing an object removes its children")
        assertTrue(scene.world.isAlive(scene.player))
    }

    @Test
    fun aFaultedSegmentDestroysNothing() {
        val program =
            Program(
                "broken",
                handlers =
                    listOf(
                        everyTick(
                            each("coin", query("score"), listOf(destroy(v("coin")))),
                            P.eval(host("bonus", int(-1)), id = "fails"),
                        ),
                    ),
            )
        val scene = Scene(program)
        scene.world.step()
        assertEquals(FaultKind.HostFailure, scene.instance.fault?.kind)
        assertTrue(scene.coins.all(scene.world::isAlive), "Removals roll back with the failed segment")
    }

    @Test
    fun destroyingAnObjectThatNoLongerExistsFaultsAtTheBlock() {
        // Remember the first coin, wait a tick, then remove it; the coin is removed by someone else meanwhile.
        val program =
            Program(
                "late",
                handlers =
                    listOf(
                        everyTick(
                            declare("coin", Expr.At(query("score"), int(0), s("first"))),
                            wait(int(1)),
                            destroy(v("coin"), id = "block:remove"),
                        ),
                    ),
            )
        val scene = Scene(program)
        scene.world.step()
        scene.world.destroy(scene.coins[0])
        scene.world.step()
        val fault = scene.instance.fault
        assertEquals(FaultKind.MissingProperty, fault?.kind)
        assertEquals("block:remove", fault?.source?.nodeId)
        assertTrue(scene.world.isAlive(scene.coins[1]), "The other coin is untouched")
    }

    @Test
    fun destroyAcceptsOnlyObjects() {
        val result =
            prepareProgram(Program("typed", handlers = listOf(everyTick(destroy(int(3), id = "block:remove")))), fixture.host)
        val diagnostic = assertIs<PrepareResult.Invalid>(result).diagnostics.single()
        assertEquals("block:remove", diagnostic.source.nodeId)
        assertTrue(diagnostic.message.contains("object"), diagnostic.message)
    }

    @Test
    fun selfRemovalStopsTheProgramWithTheObject() {
        val scene = Scene(Program("vanish", handlers = listOf(everyTick(destroy(self())))))
        scene.world.step()
        assertFalse(scene.world.isAlive(scene.player))
        scene.world.step()
    }

    @Test
    fun removalReplaysIdenticallyFromACheckpoint() {
        val program =
            Program(
                "slow",
                handlers =
                    listOf(
                        everyTick(
                            declare("coin", Expr.At(query("score"), int(0), s("first"))),
                            wait(int(2)),
                            destroy(v("coin")),
                        ),
                    ),
            )
        val live = Scene(program)
        live.world.step()
        val snapshot = live.world.snapshot()
        repeat(3) { live.world.step() }
        val replay = fixture.runtime.restore(snapshot)
        repeat(3) { replay.step() }
        assertEquals(live.world.stateHash(), replay.stateHash())
        assertFalse(replay.isAlive(live.coins[0]))
    }
}
