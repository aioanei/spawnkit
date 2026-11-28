package dev.spawnkit.ai

import kotlinx.coroutines.delay

/**
 * The reviewed live recording of a whole game built from one prompt (C13a, `ai/recordings/jungle-banana-run.json`),
 * embedded so the builder can show it to creators who are not signed in (C13b). It always plays as a labelled replay:
 * no model is called and nothing is paid. Every recorded tool call still runs against the real editor and engine.
 */
public object WholeGameReplay {
    /** The recording. */
    public val recording: RecordedSession by lazy { RecordedSession.decode(JungleBananaRunJson.text) }

    /** The creator's prompt in the recording. Ask it on a blank game, where the recorded IDs exist. */
    public val prompt: String get() = recording.script.first { it.kind == "prompt" }.text

    /**
     * A fresh replay of the recording, labelled with [RecordedSession.label]. It waits [pauseMillis] before each reply,
     * so creators can follow the steps as they arrive.
     */
    public fun model(pauseMillis: Long = 0): AgentModel {
        val replay = ReplayModel(recording)
        if (pauseMillis <= 0) return replay
        return object : AgentModel {
            override val info: ModelInfo = replay.info

            override suspend fun reply(request: ModelRequest): ModelReply {
                delay(pauseMillis)
                return replay.reply(request)
            }
        }
    }
}
