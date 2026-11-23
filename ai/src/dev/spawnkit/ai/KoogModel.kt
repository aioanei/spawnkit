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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A live model through Koog (C7): converts the session's messages and derived tool definitions to a Koog [Prompt] and
 * [ToolDescriptor]s and asks [executor]. The API key stays inside the Koog client; it is never part of [info], requests,
 * replies, recordings or tool inputs, and the agent's tools cannot read it. [maxOutputTokens], when set, caps every
 * reply, which bounds what one call can cost.
 *
 * Koog 1.3.0's Anthropic client refuses `AnyOf` parameters (reported in `docs/upstream/koog-anthropic-anyof.md`), so
 * choices are sent in a flat form and mapped back before the session sees them: a choice of objects becomes one object
 * whose discriminator (such as `block`) names the choice, and a choice of plain values becomes an object with one field
 * per kind (`number`, `whole_number`, `text`, `yes_no`).
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
                val schema = request.tools.firstOrNull { it.name == call.tool }?.inputSchema
                val input = schema?.let { restore(it, call.argsJson) as? JsonObject } ?: call.argsJson
                ToolCall(call.id ?: "call-${prompt.id}-$index", call.tool, input)
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
    val alternatives = alternatives(schema)
    if (alternatives != null) return if (alternatives.all(::isObject)) merged(alternatives) else plainChoice(alternatives)
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

private fun alternatives(schema: JsonObject): List<JsonObject>? =
    ((schema["oneOf"] ?: schema["anyOf"]) as? JsonArray)?.map { it as JsonObject }

private fun isObject(schema: JsonObject): Boolean = (schema["type"] as? JsonPrimitive)?.content == "object"

/** The property that names each object alternative, such as `block` or `kind`: a single-value enum in every variant. */
private fun discriminator(alternatives: List<JsonObject>): String? =
    (alternatives.first()["properties"] as? JsonObject)?.keys?.firstOrNull { key ->
        alternatives.all { variant -> fixedValue(variant, key) != null }
    }

private fun fixedValue(
    variant: JsonObject,
    key: String,
): String? =
    (((variant["properties"] as? JsonObject)?.get(key) as? JsonObject)?.get("enum") as? JsonArray)
        ?.singleOrNull()
        ?.jsonPrimitive
        ?.content

/**
 * A choice of objects as one object: the discriminator lists every choice with what it is for and which settings it
 * uses, and every other setting of any choice is optional. The editing tools still validate the settings of the choice.
 */
private fun merged(alternatives: List<JsonObject>): ToolParameterType {
    val key = discriminator(alternatives)
    val settings = linkedMapOf<String, JsonObject>()
    val usedBy = linkedMapOf<String, MutableList<String>>()
    val choices =
        alternatives.mapIndexed { index, variant ->
            val name = key?.let { fixedValue(variant, it) } ?: "option${index + 1}"
            val own = (variant["properties"] as? JsonObject).orEmpty().filterKeys { it != key }
            own.forEach { (setting, schema) ->
                settings[setting] = combine(settings[setting], schema as JsonObject)
                usedBy.getOrPut(setting) { mutableListOf() } += name
            }
            val help = (variant["description"] as? JsonPrimitive)?.content.orEmpty()
            val uses = if (own.isEmpty()) "" else " Settings: ${own.keys.joinToString()}."
            name to "$name: $help$uses".trim()
        }
    val parameters =
        settings.map { (setting, schema) ->
            val help = (schema["description"] as? JsonPrimitive)?.content ?: setting
            ToolParameterDescriptor(setting, "$help (for ${usedBy.getValue(setting).joinToString()})", parameterType(schema))
        }
    val chooser =
        ToolParameterDescriptor(
            key ?: "choice",
            "Choose one. ${choices.joinToString(" | ") { it.second }}",
            ToolParameterType.Enum(choices.map { it.first }.toTypedArray()),
        )
    return ToolParameterType.Object(listOf(chooser) + parameters, listOf(chooser.name), additionalProperties = false)
}

/** Two choices' schemas for the same setting: enums are joined; otherwise the first one stands. */
private fun combine(
    first: JsonObject?,
    second: JsonObject,
): JsonObject {
    if (first == null) return second
    val a = first["enum"] as? JsonArray
    val b = second["enum"] as? JsonArray
    if (a == null || b == null) return first
    return JsonObject(first + ("enum" to JsonArray((a + b).distinct())))
}

/** Field names for a choice of plain values, by JSON type. */
private val PlainFields = linkedMapOf("number" to "number", "integer" to "whole_number", "string" to "text", "boolean" to "yes_no")

/** A choice of plain values (for example a number, text or yes/no) as an object with one optional field per kind. */
private fun plainChoice(alternatives: List<JsonObject>): ToolParameterType {
    val fields =
        alternatives.mapNotNull { variant ->
            val type = (variant["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val field = PlainFields[type] ?: return@mapNotNull null
            ToolParameterDescriptor(field, "Give exactly one field, the one that matches the kind.", parameterType(variant))
        }
    return ToolParameterType.Object(fields, emptyList(), additionalProperties = false)
}

/**
 * The model's tool input in the tool's own form: flattened choices ([merged]) need nothing, and a plain-value choice
 * ([plainChoice]) is unwrapped back to its value. A model that sent the plain value directly is understood too.
 */
internal fun restore(
    schema: JsonObject,
    value: JsonElement,
): JsonElement {
    val alternatives = alternatives(schema)
    if (alternatives != null) {
        val given = value as? JsonObject ?: return value
        if (!alternatives.all(::isObject)) {
            return PlainFields.values.firstNotNullOfOrNull { given[it]?.takeUnless { v -> v is JsonNull } }
                ?: value
        }
        val key = discriminator(alternatives) ?: return value
        val chosen = (given[key] as? JsonPrimitive)?.content ?: return value
        return alternatives.firstOrNull { fixedValue(it, key) == chosen }?.let { restore(it, value) } ?: value
    }
    return when (value) {
        is JsonObject -> {
            val properties = schema["properties"] as? JsonObject ?: return value
            JsonObject(value.mapValues { (name, item) -> (properties[name] as? JsonObject)?.let { restore(it, item) } ?: item })
        }

        is JsonArray -> {
            val items = schema["items"] as? JsonObject ?: return value
            JsonArray(value.map { restore(items, it) })
        }

        else -> {
            value
        }
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
