package dev.spawnkit.ai

import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.Editor
import dev.spawnkit.gameplay.StandardEngine

/** A recorded run: the session to replay, what happened at each step in plain words, and what it cost. */
public class RecordingRun(
    /** The session, with the creator's steps and the model's replies, and no credentials. */
    public val recording: RecordedSession,
    /** One line per outcome, for example `Proposal: … (3 steps: …)`. */
    public val log: List<String>,
    /** Tokens used, and dollars for live models with known prices. */
    public val cost: Cost,
)

/**
 * Plays the creator in an assistant session about the game in [editor], so a model's work can be recorded and replayed
 * without a key. For each prompt it answers clarifying questions with their first option and accepts the proposal, as
 * a creator trying the assistant would. A failure stops the run. Recordings are made by hand from a workflow the owner
 * starts; tests never call a live model (AGENTS.md).
 */
public class SessionRecorder(
    private val engine: StandardEngine,
    private val editor: Editor,
    private val sceneId: String,
    private val maxSteps: Int = AgentSession.DEFAULT_MAX_STEPS,
) {
    /** Sends [prompts] to [model] in order and records the session, labelled with [recordedOn] and [note]. */
    public suspend fun record(
        model: AgentModel,
        prompts: List<String>,
        recordedOn: String,
        note: String,
    ): RecordingRun {
        val recorder = RecordingModel(model)
        val session = AgentSession(engine, editor, sceneId, recorder, maxSteps)
        val script = mutableListOf<ScriptStep>()
        val log = mutableListOf<String>()
        for (prompt in prompts) {
            script += ScriptStep(PROMPT, prompt)
            var outcome = session.ask(prompt)
            var answers = 0
            while (outcome is AgentOutcome.Question && answers < MAX_ANSWERS) {
                val answer = outcome.options.firstOrNull() ?: OPEN_ANSWER
                log += "Question: ${outcome.question} → answered \"$answer\""
                script += ScriptStep(ANSWER, answer)
                answers++
                outcome = session.answer(answer)
            }
            log += conclude(session, prompt, outcome)
            if (outcome is AgentOutcome.Failed || outcome is AgentOutcome.Question || outcome is AgentOutcome.Confirmation) break
        }
        return RecordingRun(recorder.recording(script, recordedOn, note), log, session.cost)
    }

    /** Replays [recording] step by step, accepting proposals where the recording did; returns the same kind of log. */
    public suspend fun replay(recording: RecordedSession): List<String> {
        val session = AgentSession(engine, editor, sceneId, ReplayModel(recording), maxSteps)
        val log = mutableListOf<String>()
        var prompt = ""
        recording.script.forEachIndexed { index, step ->
            val outcome =
                if (step.kind == ANSWER) {
                    session.answer(step.text)
                } else {
                    prompt = step.text
                    session.ask(step.text)
                }
            if (outcome is AgentOutcome.Question && recording.script.getOrNull(index + 1)?.kind == ANSWER) {
                log += "Question: ${outcome.question} → answered \"${recording.script[index + 1].text}\""
            } else {
                log += conclude(session, prompt, outcome)
            }
        }
        return log
    }

    private fun conclude(
        session: AgentSession,
        prompt: String,
        outcome: AgentOutcome,
    ): String =
        when (outcome) {
            is AgentOutcome.Proposal -> {
                val steps = "${outcome.steps.size} steps: ${outcome.steps.joinToString("; ")}"
                when (val accepted = session.accept("Assistant: $prompt")) {
                    is AcceptResult.Accepted -> "Proposal: ${outcome.summary} ($steps)"
                    else -> "Proposal not accepted: ${(accepted as? AcceptResult.Refused)?.message ?: accepted} ($steps)"
                }
            }

            is AgentOutcome.Answer -> {
                "Answer: ${outcome.text}"
            }

            is AgentOutcome.Question -> {
                "Question left open: ${outcome.question}"
            }

            is AgentOutcome.Confirmation -> {
                // Recordings never send photos: the recorder has no character maker, so this is not expected.
                "Confirmation left open: ${outcome.message}"
            }

            is AgentOutcome.Failed -> {
                "Failed: ${outcome.message}"
            }
        }

    private companion object {
        const val PROMPT = "prompt"
        const val ANSWER = "answer"
        const val MAX_ANSWERS = 3
        const val OPEN_ANSWER = "Use your best judgment."
    }
}
