package dev.spawnkit.engine

import dev.spawnkit.model.DecodeResult
import dev.spawnkit.model.SpawnJson
import dev.spawnkit.model.VariableId
import dev.spawnkit.templates.EmbeddedTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cross-target determinism check (ADR-0004). The same scripted run of the Meadow level must end in exactly the same
 * state on JVM, JS, Wasm, mingwX64, linuxX64 and the iOS simulator.
 *
 * [GOLDEN_MEADOW_HASH] was recorded from a JVM run. If an intentional engine or template change alters it, re-record
 * it from the JVM failure message and verify every target agrees. A mismatch on only some targets is a determinism bug
 * in the engine: fix the engine, never add per-platform golden values.
 */
class DeterminismGoldenTest {
    @Test
    fun meadowHopRunHashMatchesGolden() {
        val project = assertIs<DecodeResult.Ok>(SpawnJson.decode(EmbeddedTemplates.all.getValue("platformer-adventure"))).project
        val session = GameSession(project)
        val events = InputScripts.hopRightMeadow.flatMap { session.step(it) }

        assertTrue(session.world.variable(VariableId("coins")) >= 3.0, "the run should collect coins")
        assertTrue(events.isNotEmpty())
        assertEquals(GOLDEN_MEADOW_HASH, session.stateHash(), "state hash after ${session.world.tick} ticks")
    }

    private companion object {
        const val GOLDEN_MEADOW_HASH: Long = -5121317281474289210L
    }
}
