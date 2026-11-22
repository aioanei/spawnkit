package dev.spawnkit.tools.recorder

import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.ModelInfo
import dev.spawnkit.ai.ModelReply
import dev.spawnkit.ai.ModelRequest
import dev.spawnkit.ai.RecordedSession
import dev.spawnkit.ai.Usage
import dev.spawnkit.editing.ProjectCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The recorder command, driven by the keyless demo model; tests never call a live model (AGENTS.md). */
class RecorderTest {
    private val out: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build")), "recordings-")

    @Test
    fun aScenarioWritesItsRecordingAndTheGameItMade() {
        val lines = mutableListOf<String>()
        val scenario = Scenario("demo", "coin-dash", DemoModel.PROMPTS.take(2), "Demo check")

        val ok = recordAll(DemoModel(), listOf(scenario), out, "2026-11-22", lines::add)

        assertTrue(ok, lines.joinToString("\n"))
        val recording = RecordedSession.decode(out.resolve("demo.json").readText())
        assertEquals("Scripted demo", recording.model)
        assertEquals(2, recording.script.size)
        val game = ProjectCodec.decode(out.resolve("demo.spawnkit").readText())
        assertEquals("Coin Dash", game.name)
        assertTrue(lines.any { it.startsWith("  Proposal") }, lines.joinToString("\n"))
    }

    @Test
    fun aFailedScenarioIsReportedAndTheRunFails() {
        val lines = mutableListOf<String>()
        val failing =
            object : AgentModel {
                override val info = ModelInfo("Failing fake", "none", live = false, label = "Failing fake")

                override suspend fun reply(request: ModelRequest): ModelReply = error("offline")
            }

        val ok = recordAll(failing, listOf(Scenario("broken", null, listOf("Anything"), "Failure check")), out, "2026-11-22", lines::add)

        assertEquals(false, ok)
        assertTrue(lines.any { "Failed" in it }, lines.joinToString("\n"))
    }

    @Test
    fun theBudgetStopsCallsOnceSpent() {
        val pricey =
            object : AgentModel {
                override val info = ModelInfo("Pricey fake", "Test", live = true, label = "Pricey fake", 3.0, 15.0)

                override suspend fun reply(request: ModelRequest): ModelReply = ModelReply("ok", emptyList(), Usage(inputTokens = 200_000))
            }
        val capped = CappedModel(pricey, maxDollars = 1.0)

        runBlocking {
            repeat(2) { capped.reply(ModelRequest("system", emptyList(), emptyList())) }
            assertFailsWith<IllegalStateException> { capped.reply(ModelRequest("system", emptyList(), emptyList())) }
        }
        assertEquals(1.2, capped.spent, 1e-9)
    }

    @Test
    fun theKeyIsHiddenInReportedLines() {
        // GitHub hides secrets in logs, but not inside uploaded artifacts such as the summary file.
        assertEquals("Failed: bad key [key hidden] rejected", hideKey("Failed: bad key sk-test-123 rejected", "sk-test-123"))
        assertEquals("Nothing to hide", hideKey("Nothing to hide", "sk-test-123"))
    }

    @Test
    fun demoScenariosUseExistingPresets() {
        val presets = setOf("coin-dash", "tap-targets", "maze-run")
        assertTrue(DEMO_SCENARIOS.isNotEmpty())
        DEMO_SCENARIOS.forEach { scenario ->
            assertTrue(scenario.preset == null || scenario.preset in presets, scenario.name)
            assertTrue(scenario.prompts.isNotEmpty() && scenario.prompts.all { it.isNotBlank() }, scenario.name)
        }
    }
}
