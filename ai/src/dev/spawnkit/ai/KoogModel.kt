package dev.spawnkit.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import dev.spawnkit.editing.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A live model through Koog (C7): converts the session's messages and derived tool definitions to a Koog [Prompt] and
 * [ToolDescriptor]s and asks [executor]. The API key stays inside the Koog client; it is never part of [info], requests,
 * replies, recordings or tool inputs, and the agent's tools cannot read it. [maxOutputTokens], when set, caps every
 * reply, which bounds what one call can cost.
 */
public class KoogModel(
    private val executor: PromptExecutor,
    private val model: LLModel,
    override val info: ModelInfo,
    private val maxOutputTokens: Int? = null,
) : AgentModel {
    private var promptCounter = 0

    override suspend fun reply(request: ModelRequest): ModelReply {
        val prompt = Prompt(koogMessages(request), "spawnkit-assistant-${promptCounter++}", LLMParams(maxTokens = maxOutputTokens))
        val response = executor.execute(prompt, model, request.tools.map(::toolDescriptor))
        val text =
            response.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.text }
                .ifBlank { null }
        val calls =
            response.parts.filterIsInstance<MessagePart.Tool.Call>().mapIndexed { index, call ->
                ToolCall(call.id ?: "call-${prompt.id}-$index", call.tool, call.argsJson)
            }
        val meta = response.metaInfo
        return ModelReply(text, calls, Usage(meta.inputTokensCount?.toLong() ?: 0, meta.outputTokensCount?.toLong() ?: 0))
    }

    /** Factories for supported providers. */
    public companion object {
        /** Default Anthropic model for the assistant. */
        public const val DEFAULT_ANTHROPIC_MODEL: String = "claude-sonnet-5-5"

        /**
         * An Anthropic model through Koog. [apiKey] comes from device secure storage or the environment, never from a
         * project. Prices are per million tokens and only used to show an estimate. [maxOutputTokens] caps each reply.
         */
        public fun anthropic(
            apiKey: String,
            modelId: String = DEFAULT_ANTHROPIC_MODEL,
            inputPricePerMillion: Double? = 3.0,
            outputPricePerMillion: Double? = 15.0,
            maxOutputTokens: Int? = null,
        ): KoogModel {
            require(apiKey.isNotBlank()) { "Add an Anthropic API key in the assistant settings first" }
            val base = AnthropicModels.Sonnet_5
            val model = LLModel(LLMProvider.Anthropic, modelId, base.capabilities, base.contextLength, base.maxOutputTokens)
            val settings = AnthropicClientSettings(modelVersionsMap = mapOf(model to modelId))
            val client = AnthropicLLMClient(apiKey, settings, KtorKoogHttpClient.Factory())
            val info =
                ModelInfo(
                    modelId,
                    "Anthropic",
                    live = true,
                    label = "Live: $modelId (Anthropic)",
                    inputPricePerMillion,
                    outputPricePerMillion,
                )
            return KoogModel(MultiLLMPromptExecutor(client), model, info, maxOutputTokens)
        }
    }
}

/** The session conversation as Koog messages: tool results go back in a user message, as providers expect. */
internal fun koogMessages(request: ModelRequest): List<Message> =
    listOf<Message>(Message.System(listOf(MessagePart.Text(request.system)), RequestMetaInfo.Empty)) +
        request.messages.map { message ->
            when (message) {
                is AgentMessage.User -> {
                    Message.User(listOf(MessagePart.Text(message.text)), RequestMetaInfo.Empty)
                }

                is AgentMessage.Assistant -> {
                    Message.Assistant(
                        listOfNotNull(message.text?.let { MessagePart.Text(it) }) +
                            message.calls.map { MessagePart.Tool.Call(it.id, it.name, it.input) },
                        ResponseMetaInfo.Empty,
                    )
                }

                is AgentMessage.ToolReplies -> {
                    Message.User(message.replies.map { MessagePart.Tool.Result(it.callId, it.name, it.text) }, RequestMetaInfo.Empty)
                }
            }
        }

/** A derived tool definition as a Koog descriptor; its JSON Schema maps onto Koog parameter types. */
internal fun toolDescriptor(definition: ToolDefinition): ToolDescriptor {
    val schema = definition.inputSchema
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
    val parameters = properties.map { (name, value) -> parameter(name, value as JsonObject) }
    return ToolDescriptor(
        definition.name,
        definition.description,
        requiredParameters = parameters.filter { it.name in required },
        optionalParameters = parameters.filter { it.name !in required },
    )
}

private fun parameter(
    name: String,
    schema: JsonObject,
): ToolParameterDescriptor =
    ToolParameterDescriptor(name, (schema["description"] as? JsonPrimitive)?.content ?: name, parameterType(schema))

private fun parameterType(schema: JsonObject): ToolParameterType {
    val alternatives = (schema["oneOf"] ?: schema["anyOf"]) as? JsonArray
    if (alternatives != null) {
        return ToolParameterType.AnyOf(
            alternatives
                .mapIndexed { index, option ->
                    val variant = option as JsonObject
                    ToolParameterDescriptor(
                        variantName(variant) ?: "option${index + 1}",
                        (variant["description"] as? JsonPrimitive)?.content ?: "Option ${index + 1}",
                        parameterType(variant),
                    )
                }.toTypedArray(),
        )
    }
    val enum = schema["enum"] as? JsonArray
    if (enum != null) return ToolParameterType.Enum(enum.map { it.jsonPrimitive.content }.toTypedArray())
    return when ((schema["type"] as? JsonPrimitive)?.content) {
        "integer" -> {
            ToolParameterType.Integer
        }

        "number" -> {
            ToolParameterType.Float
        }

        "boolean" -> {
            ToolParameterType.Boolean
        }

        "null" -> {
            ToolParameterType.Null
        }

        "array" -> {
            ToolParameterType.List((schema["items"] as? JsonObject)?.let(::parameterType) ?: ToolParameterType.String)
        }

        "object" -> {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            val additional = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull
            ToolParameterType.Object(
                properties.map { (name, value) -> parameter(name, value as JsonObject) },
                required,
                additionalProperties = additional ?: properties.isEmpty(),
                additionalPropertiesType = if (properties.isEmpty() && additional != false) ToolParameterType.String else null,
            )
        }

        else -> {
            ToolParameterType.String
        }
    }
}

/** The fixed discriminator value of a variant (for example a block ID), used to name the alternative. */
private fun variantName(variant: JsonObject): String? =
    (variant["properties"] as? JsonObject)
        ?.values
        ?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("enum") as? JsonArray)?.singleOrNull()?.jsonPrimitive?.content }
