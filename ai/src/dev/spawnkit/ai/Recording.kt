package dev.spawnkit.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One step of a recorded conversation: what the creator typed. */
public data class ScriptStep(
    /** `prompt` (a new request) or `answer` (to a clarifying question). */
    public val kind: String,
    /** Text the creator typed. */
    public val text: String,
)

/**
 * A recorded assistant session: the creator's prompts and the model's replies, without any credentials. Replaying it
 * re-runs every tool call against the real editor and engine, so changes, validation and traces are real; only the
 * model's choices are replayed. [model] and [recordedOn] label every replay.
 */
public data class RecordedSession(
    /** Model that produced the replies, or a description such as "Scripted fixture" for hand-written sessions. */
    public val model: String,
    /** Provider, or "none" for hand-written sessions. */
    public val provider: String,
    /** Date of recording, `YYYY-MM-DD`. */
    public val recordedOn: String,
    /** What this session shows, and how it was made. */
    public val note: String,
    /** What the creator typed, in order. */
    public val script: List<ScriptStep>,
    /** Model replies, in order. */
    public val replies: List<ModelReply>,
) {
    /** The label every replay of this session shows. */
    public val label: String get() = "Replay: $model, recorded $recordedOn (no model is called)"

    /** Storage. */
    public companion object {
        /** Format marker of recording files. */
        public const val FORMAT: String = "spawnkit-agent-recording"

        /** Encodes [session] as JSON text. */
        public fun encode(session: RecordedSession): String =
            JsonObject(
                mapOf(
                    "format" to JsonPrimitive(FORMAT),
                    "version" to JsonPrimitive(1),
                    "model" to JsonPrimitive(session.model),
                    "provider" to JsonPrimitive(session.provider),
                    "recordedOn" to JsonPrimitive(session.recordedOn),
                    "note" to JsonPrimitive(session.note),
                    "script" to
                        JsonArray(
                            session.script.map { JsonObject(mapOf("kind" to JsonPrimitive(it.kind), "text" to JsonPrimitive(it.text))) },
                        ),
                    "replies" to JsonArray(session.replies.map(::reply)),
                ),
            ).toString()

        /** Decodes a recording; malformed or foreign files fail with an explanation. */
        public fun decode(text: String): RecordedSession {
            val root =
                runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw IllegalArgumentException("This is not an assistant recording")
            require(root["format"]?.jsonPrimitive?.content == FORMAT) { "This is not an assistant recording" }
            require(root["version"]?.jsonPrimitive?.longOrNull == 1L) { "This recording needs a newer SpawnKit" }
            return try {
                RecordedSession(
                    root.text("model"),
                    root.text("provider"),
                    root.text("recordedOn"),
                    root.text("note"),
                    root.getValue("script").jsonArray.map { ScriptStep(it.jsonObject.text("kind"), it.jsonObject.text("text")) },
                    root.getValue("replies").jsonArray.map { decodeReply(it.jsonObject) },
                )
            } catch (problem: RuntimeException) {
                throw IllegalArgumentException("This assistant recording is damaged: ${problem.message}")
            }
        }

        private fun reply(reply: ModelReply): JsonElement =
            JsonObject(
                mapOf(
                    "text" to (reply.text?.let(::JsonPrimitive) ?: JsonNull),
                    "calls" to
                        JsonArray(
                            reply.calls.map {
                                JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name), "input" to it.input))
                            },
                        ),
                    "usage" to
                        JsonObject(
                            mapOf(
                                "input" to JsonPrimitive(reply.usage.inputTokens),
                                "output" to JsonPrimitive(reply.usage.outputTokens),
                            ),
                        ),
                ),
            )

        private fun decodeReply(item: JsonObject): ModelReply {
            val usage = item["usage"]?.jsonObject
            return ModelReply(
                (item["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                item.getValue("calls").jsonArray.map {
                    val call = it.jsonObject
                    ToolCall(call.text("id"), call.text("name"), call.getValue("input").jsonObject)
                },
                Usage(
                    usage?.get("input")?.jsonPrimitive?.longOrNull ?: 0,
                    usage?.get("output")?.jsonPrimitive?.longOrNull ?: 0,
                ),
            )
        }

        private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    }
}

/** Raised when a replay no longer fits the tools it is given, for example after tools were renamed. */
public class ReplayMismatch(
    message: String,
) : IllegalStateException(message)

/**
 * Replays a [RecordedSession] without keys or network. It is labelled as a replay with the recorded model and date,
 * reports the recorded token usage but no cost, and refuses to go on when a recorded call names a tool that no longer
 * exists.
 */
public class ReplayModel(
    private val recording: RecordedSession,
) : AgentModel {
    private var next = 0

    override val info: ModelInfo = ModelInfo(recording.model, "Replay", live = false, label = recording.label)

    /** Whether every recorded reply was used. */
    public val finished: Boolean get() = next >= recording.replies.size

    override suspend fun reply(request: ModelRequest): ModelReply {
        val reply = recording.replies.getOrNull(next) ?: throw ReplayMismatch("The recording has no more replies")
        val names = request.tools.map { it.name }.toSet()
        reply.calls.firstOrNull { it.name !in names }?.let {
            throw ReplayMismatch(
                "The recording calls '${it.name}', which no longer exists",
            )
        }
        next++
        return reply
    }
}

/**
 * Wraps a live model and records its replies, so a session can later be replayed without keys. Requests are not
 * recorded (the replay re-creates them), and credentials never pass through this class.
 */
public class RecordingModel(
    private val delegate: AgentModel,
) : AgentModel {
    private val replies = mutableListOf<ModelReply>()

    override val info: ModelInfo get() = delegate.info

    override suspend fun reply(request: ModelRequest): ModelReply = delegate.reply(request).also { replies += it }

    /** The recording so far, labelled with [recordedOn] (`YYYY-MM-DD`) and [note]. */
    public fun recording(
        script: List<ScriptStep>,
        recordedOn: String,
        note: String,
    ): RecordedSession = RecordedSession(delegate.info.name, delegate.info.provider, recordedOn, note, script, replies.toList())
}
