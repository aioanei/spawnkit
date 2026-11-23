package dev.spawnkit.ai

import dev.spawnkit.editing.ToolDefinition
import kotlinx.serialization.json.JsonObject

/** A tool call made by a model. */
public data class ToolCall(
    /** Call ID chosen by the model provider, echoed in the reply. */
    public val id: String,
    /** Tool name. */
    public val name: String,
    /** JSON input. */
    public val input: JsonObject,
)

/** The result of a [ToolCall], given back to the model. */
public data class ToolReply(
    /** ID of the call this answers. */
    public val callId: String,
    /** Tool name. */
    public val name: String,
    /** Result text. */
    public val text: String,
)

/** One message of an agent conversation. */
public sealed interface AgentMessage {
    /** The creator's prompt or answer. */
    public data class User(
        /** Text. */
        public val text: String,
    ) : AgentMessage

    /** The model's text and tool calls. */
    public data class Assistant(
        /** Text, or `null`. */
        public val text: String?,
        /** Tool calls, in order. */
        public val calls: List<ToolCall>,
    ) : AgentMessage

    /** Results of every call of the previous assistant message. */
    public data class ToolReplies(
        /** Results, in call order. */
        public val replies: List<ToolReply>,
    ) : AgentMessage
}

/**
 * Tokens used by model calls. With prompt caching, [inputTokens] counts only the uncached input: [cacheWriteTokens]
 * were written to the provider's cache (priced above input) and [cacheReadTokens] were read from it (far below).
 */
public data class Usage(
    /** Input tokens, not counting cached ones. */
    public val inputTokens: Long = 0,
    /** Output tokens. */
    public val outputTokens: Long = 0,
    /** Input tokens written to the prompt cache. */
    public val cacheWriteTokens: Long = 0,
    /** Input tokens read from the prompt cache. */
    public val cacheReadTokens: Long = 0,
) {
    /** Sum of two usages. */
    public operator fun plus(other: Usage): Usage =
        Usage(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            cacheWriteTokens + other.cacheWriteTokens,
            cacheReadTokens + other.cacheReadTokens,
        )

    /** Prompt-cache prices relative to the input price (Anthropic: writes 1.25 ×, reads 0.1 ×). */
    public companion object {
        /** Price of a cache write, as a multiple of the input price. */
        public const val CACHE_WRITE_FACTOR: Double = 1.25

        /** Price of a cache read, as a multiple of the input price. */
        public const val CACHE_READ_FACTOR: Double = 0.1
    }
}

/** What one model call asks for. */
public data class ModelRequest(
    /** Instructions for the model. */
    public val system: String,
    /** Conversation so far. */
    public val messages: List<AgentMessage>,
    /** Tools the model may call. */
    public val tools: List<ToolDefinition>,
)

/** One model reply: text and tool calls. */
public data class ModelReply(
    /** Text, or `null`. */
    public val text: String?,
    /** Tool calls, in order. */
    public val calls: List<ToolCall>,
    /** Tokens this reply used. */
    public val usage: Usage = Usage(),
)

/**
 * Which model answers, shown to the creator. [live] is false for replays, which must stay labelled as replays
 * (AGENTS.md). Prices are per million tokens, in US dollars, when known.
 */
public data class ModelInfo(
    /** Model name, for example a provider model ID, or the recorded model of a replay. */
    public val name: String,
    /** Provider, for example "Anthropic", or "Replay". */
    public val provider: String,
    /** Whether a real model is called. */
    public val live: Boolean,
    /** Label shown in the assistant, for example "Replay of … recorded 2026-11-21". */
    public val label: String,
    /** Input price per million tokens, or `null`. */
    public val inputPricePerMillion: Double? = null,
    /** Output price per million tokens, or `null`. */
    public val outputPricePerMillion: Double? = null,
)

/** Estimated cost of a session. */
public data class Cost(
    /** Tokens used. */
    public val usage: Usage,
    /** Estimated US dollars, or `null` when prices are unknown (or for replays, which cost nothing). */
    public val dollars: Double?,
) {
    /** Factory. */
    public companion object {
        /** The cost of [usage] for [model]; replays and unknown prices give no dollar amount. */
        public fun of(
            usage: Usage,
            model: ModelInfo,
        ): Cost {
            val input = model.inputPricePerMillion
            val output = model.outputPricePerMillion
            val dollars =
                if (!model.live || input == null || output == null) {
                    null
                } else {
                    (
                        usage.inputTokens * input +
                            usage.outputTokens * output +
                            usage.cacheWriteTokens * input * Usage.CACHE_WRITE_FACTOR +
                            usage.cacheReadTokens * input * Usage.CACHE_READ_FACTOR
                    ) / 1_000_000.0
                }
            return Cost(usage, dollars)
        }
    }
}

/**
 * A model an agent session can ask. Implementations: [ReplayModel] (recorded sessions, no keys), [RecordingModel]
 * (wraps a live model and records it) and the Koog adapter for live providers. Implementations never put credentials
 * into requests, replies or recordings.
 */
public interface AgentModel {
    /** Which model this is. */
    public val info: ModelInfo

    /** Answers [request]. Suspends while the provider works; cancelling the caller cancels the call. */
    public suspend fun reply(request: ModelRequest): ModelReply
}
