package dev.spawnkit.ai

import dev.spawnkit.editing.ToolDefinition
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The wire format between [HostedModel] and SpawnKit's server (ADR-0011): one [ModelRequest] out, one [ModelReply]
 * back, as plain JSON objects. Decoding refuses anything malformed with an [IllegalArgumentException].
 *
 * - A request is `{"system", "messages", "tools"}`. Messages have a `role`: `user` (`text`), `assistant` (`text`,
 *   `calls`) or `tools` (`replies`).
 * - A reply is `{"text", "calls", "usage": {"inputTokens", "outputTokens", "cacheWriteTokens", "cacheReadTokens"}}`;
 *   the cache counts are optional.
 */
public object ModelRequestJson {
    /** [request] as JSON text. */
    public fun encodeRequest(request: ModelRequest): String =
        buildJsonObject {
            put("system", JsonPrimitive(request.system))
            put("messages", JsonArray(request.messages.map(::message)))
            put("tools", JsonArray(request.tools.map(::tool)))
        }.toString()

    /** The request in [text]. */
    public fun decodeRequest(text: String): ModelRequest =
        decoding {
            val root = parse(text)
            ModelRequest(
                system = root.string("system"),
                messages = root.array("messages").map { message(it.obj()) },
                tools = root.array("tools").map { tool(it.obj()) },
            )
        }

    /** [reply] as JSON text. */
    public fun encodeReply(reply: ModelReply): String =
        buildJsonObject {
            put("text", reply.text?.let(::JsonPrimitive) ?: JsonNull)
            put("calls", JsonArray(reply.calls.map(::call)))
            put(
                "usage",
                buildJsonObject {
                    put("inputTokens", JsonPrimitive(reply.usage.inputTokens))
                    put("outputTokens", JsonPrimitive(reply.usage.outputTokens))
                    put("cacheWriteTokens", JsonPrimitive(reply.usage.cacheWriteTokens))
                    put("cacheReadTokens", JsonPrimitive(reply.usage.cacheReadTokens))
                },
            )
        }.toString()

    /** The reply in [text]. */
    public fun decodeReply(text: String): ModelReply =
        decoding {
            val root = parse(text)
            val usage = root["usage"] as? JsonObject
            ModelReply(
                text = root.optionalString("text"),
                calls = root.array("calls").map { call(it.obj()) },
                usage =
                    Usage(
                        usage?.long("inputTokens") ?: 0,
                        usage?.long("outputTokens") ?: 0,
                        usage?.long("cacheWriteTokens") ?: 0,
                        usage?.long("cacheReadTokens") ?: 0,
                    ),
            )
        }

    private fun message(message: AgentMessage): JsonObject =
        when (message) {
            is AgentMessage.User -> {
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("text", JsonPrimitive(message.text))
                }
            }

            is AgentMessage.Assistant -> {
                buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("text", message.text?.let(::JsonPrimitive) ?: JsonNull)
                    put("calls", JsonArray(message.calls.map(::call)))
                }
            }

            is AgentMessage.ToolReplies -> {
                buildJsonObject {
                    put("role", JsonPrimitive("tools"))
                    put(
                        "replies",
                        JsonArray(
                            message.replies.map {
                                buildJsonObject {
                                    put("callId", JsonPrimitive(it.callId))
                                    put("name", JsonPrimitive(it.name))
                                    put("text", JsonPrimitive(it.text))
                                }
                            },
                        ),
                    )
                }
            }
        }

    private fun message(json: JsonObject): AgentMessage =
        when (val role = json.string("role")) {
            "user" -> {
                AgentMessage.User(json.string("text"))
            }

            "assistant" -> {
                AgentMessage.Assistant(json.optionalString("text"), json.array("calls").map { call(it.obj()) })
            }

            "tools" -> {
                AgentMessage.ToolReplies(
                    json.array("replies").map {
                        it.obj().let { r ->
                            ToolReply(r.string("callId"), r.string("name"), r.string("text"))
                        }
                    },
                )
            }

            else -> {
                throw IllegalArgumentException("Unknown message role '$role'")
            }
        }

    private fun call(call: ToolCall): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(call.id))
            put("name", JsonPrimitive(call.name))
            put("input", call.input)
        }

    private fun call(json: JsonObject): ToolCall = ToolCall(json.string("id"), json.string("name"), json["input"].obj())

    private fun tool(tool: ToolDefinition): JsonObject =
        buildJsonObject {
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.description))
            put("inputSchema", tool.inputSchema)
        }

    private fun tool(json: JsonObject): ToolDefinition =
        ToolDefinition(json.string("name"), json.string("description"), json["inputSchema"].obj())

    private inline fun <T> decoding(block: () -> T): T =
        try {
            block()
        } catch (problem: SerializationException) {
            throw IllegalArgumentException("The AI message is not valid JSON", problem)
        }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).obj()

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected a JSON object")

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Expected text in '$key'")

    private fun JsonObject.optionalString(key: String): String? =
        when (val value = get(key)) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull ?: throw IllegalArgumentException("Expected text in '$key'")
            else -> throw IllegalArgumentException("Expected text in '$key'")
        }

    private fun JsonObject.array(key: String): JsonArray =
        get(key) as? JsonArray ?: throw IllegalArgumentException("Expected a list in '$key'")

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
}
