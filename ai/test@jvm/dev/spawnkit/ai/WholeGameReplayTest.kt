package dev.spawnkit.ai

import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reviewed live recording of a whole game built from one prompt (C13a, `ai/recordings/jungle-banana-run.json`).
 * Replaying it calls no model: every recorded tool call runs against today's real editor and engine and must rebuild
 * exactly the game the live session made. It breaks when a tool changes in a way the recording no longer fits.
 */
class WholeGameReplayTest {
    /** Gradle runs tests in the module directory and the Kotlin Toolchain in the repository root. */
    private fun file(name: String): File = listOf(File("recordings/$name"), File("ai/recordings/$name")).first { it.exists() }

    private val recording = RecordedSession.decode(file("jungle-banana-run.json").readText())

    @Test
    fun theLiveWholeGameReplaysToTheSameGame() =
        runBlocking {
            val engine = StandardEngine()
            val project = Project.blank()
            val editor = Editor(engine.runtime, project, programs = engine.programs)
            val log = SessionRecorder(engine, editor, project.scenes.first().id).replay(recording)

            assertTrue(log.single().startsWith("Proposal: Built \"Jungle Banana Run\""), log.joinToString())
            val recorded = ProjectCodec.decode(file("jungle-banana-run.spawnkit").readText())
            assertEquals(recorded, editor.saveSnapshot())
            assertEquals(
                listOf("Title", "Level 1", "Level 2", "Shop", "Jungle Champion", "Game Over"),
                editor.project.scenes.map { it.name },
            )
        }

    @Test
    fun theEmbeddedCopyMatchesTheReviewedFile() {
        // Regenerate with `python tools/embed-recording.py` after the recording changes.
        assertEquals(file("jungle-banana-run.json").readText().replace("\r\n", "\n"), JungleBananaRunJson.text)
        assertEquals(recording, WholeGameReplay.recording)
        assertEquals(recording.script.single().text, WholeGameReplay.prompt)
    }

    @Test
    fun theBuilderReplayBuildsTheWholeGameOnABlankProject() =
        runBlocking {
            val engine = StandardEngine()
            val editor = Editor(engine.runtime, Project.blank(), programs = engine.programs)
            val session =
                AgentSession(
                    engine,
                    editor,
                    editor.project.scenes
                        .first()
                        .id,
                    WholeGameReplay.model(pauseMillis = 1),
                )

            val outcome = session.ask(WholeGameReplay.prompt)

            val proposal = assertIs<AgentOutcome.Proposal>(outcome)
            assertTrue(session.modelInfo.label.startsWith("Replay:"), session.modelInfo.label)
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: replay"))
            assertEquals(6, editor.project.scenes.size, proposal.summary)
        }

    @Test
    fun theReplayIsLabelledAndCarriesNoKey() {
        val label = ReplayModel(recording).info
        assertFalse(label.live)
        assertTrue("Replay" in label.label && "claude-sonnet-5-5" in label.label, label.label)
        assertFalse("sk-ant" in file("jungle-banana-run.json").readText())
    }
}
