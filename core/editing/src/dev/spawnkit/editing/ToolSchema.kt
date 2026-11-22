package dev.spawnkit.editing

import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.ValueKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * An agent tool derived from an [OperationSpec]: a name, a description for the model and a JSON Schema for its input.
 * Agent frameworks adapt it unchanged, so a new operation, component or rule block becomes promptable without new
 * agent code (ADR-0009).
 */
public class ToolDefinition(
    /** Tool name; the operation ID. */
    public val name: String,
    /** What the tool does, with examples, for the model. */
    public val description: String,
    /** JSON Schema (draft 2020-12 subset) of the tool's input object. */
    public val inputSchema: JsonObject,
)

/** The agent tool for this operation. Component choices come from [runtime]'s installed descriptors. */
public fun OperationSpec.toolDefinition(runtime: Runtime): ToolDefinition =
    ToolDefinition(
        id,
        buildString {
            append(authoring.name).append(". ").append(authoring.description)
            append(" Examples: ").append(examples.joinToString("; ") { "\"$it\"" }).append('.')
        },
        objectSchema(parameters, runtime),
    )

/** Agent tools for every entry, in catalog order. */
public fun OperationCatalog.toolDefinitions(runtime: Runtime): List<ToolDefinition> = operations.map { it.toolDefinition(runtime) }

/** Builds operation [id] from a tool call's JSON [input], exactly as [OperationCatalog.build] does from arguments. */
public fun OperationCatalog.buildFromJson(
    id: String,
    input: JsonObject,
    context: CatalogContext,
): BuildResult {
    val spec = get(id) ?: return BuildResult.Rejected("There is no operation called '$id'")
    return spec.build(decodeArguments(spec.parameters, input), context)
}

/**
 * Converts a JSON tool input into catalog arguments for [parameters]: variants become [Picked], numbers `Long` or
 * `Double`, and nested objects maps. Values are checked later by [checkArguments].
 */
public fun decodeArguments(
    parameters: List<Parameter>,
    input: JsonObject,
): Map<String, Any?> =
    input.mapValues { (key, element) ->
        val parameter = parameters.firstOrNull { it.id == key }
        if (parameter == null) plainJson(element) else decode(parameter.type, element)
    }

/** Converts catalog arguments to JSON tool input; the inverse of [decodeArguments] for normalized values. */
public fun encodeArguments(
    parameters: List<Parameter>,
    arguments: Map<String, Any?>,
): JsonObject =
    JsonObject(
        arguments.filterValues { it != null }.mapValues { (key, value) ->
            val type = parameters.firstOrNull { it.id == key }?.type
            encode(type, checkNotNull(value))
        },
    )

private fun decode(
    type: ParameterType,
    element: JsonElement,
): Any? =
    when (type) {
        is ParameterType.OneOf -> {
            when {
                element is JsonObject && element[type.discriminator] is JsonPrimitive -> {
                    val id = (element.getValue(type.discriminator) as JsonPrimitive).content
                    val nested = type.variant(id)?.parameters.orEmpty()
                    Picked(id, decodeArguments(nested, JsonObject(element - type.discriminator)))
                }

                element is JsonPrimitive && element.isString -> {
                    Picked(element.content)
                }

                else -> {
                    plainJson(element)
                }
            }
        }

        is ParameterType.ListOf -> {
            if (element is JsonArray) element.map { decode(type.item, it) } else plainJson(element)
        }

        else -> {
            plainJson(element)
        }
    }

private fun plainJson(element: JsonElement): Any? =
    when (element) {
        JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull
        is JsonArray -> element.map(::plainJson)
        is JsonObject -> element.mapValues { plainJson(it.value) }
    }

private fun encode(
    type: ParameterType?,
    value: Any,
): JsonElement =
    when (value) {
        is Picked -> {
            val oneOf = type as? ParameterType.OneOf
            val discriminator = oneOf?.discriminator ?: "kind"
            val nested = oneOf?.variant(value.variant)?.parameters.orEmpty()
            JsonObject(mapOf(discriminator to JsonPrimitive(value.variant)) + encodeArguments(nested, value.arguments))
        }

        is ComponentDoc -> {
            JsonObject(
                mapOf(
                    COMPONENT to JsonPrimitive(value.componentId),
                    "values" to JsonObject(value.values.mapValues { encode(null, it.value) }),
                ),
            )
        }

        is List<*> -> {
            JsonArray(value.map { encode((type as? ParameterType.ListOf)?.item ?: type, checkNotNull(it)) })
        }

        is Map<*, *> -> {
            JsonObject(value.entries.associate { (k, v) -> k.toString() to (v?.let { encode(null, it) } ?: JsonNull) })
        }

        is String -> {
            JsonPrimitive(value)
        }

        is Boolean -> {
            JsonPrimitive(value)
        }

        is Long -> {
            JsonPrimitive(value)
        }

        is Int -> {
            JsonPrimitive(value)
        }

        is Double -> {
            JsonPrimitive(value)
        }

        else -> {
            JsonPrimitive(value.toString())
        }
    }

private fun objectSchema(
    parameters: List<Parameter>,
    runtime: Runtime,
    extra: Map<String, JsonElement> = emptyMap(),
    required: List<String> = emptyList(),
): JsonObject =
    JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("object"),
            "properties" to
                JsonObject(
                    extra + parameters.associate { parameter -> parameter.id to parameterSchema(parameter, runtime) },
                ),
            "required" to JsonArray((required + parameters.filter { it.required }.map { it.id }).map(::JsonPrimitive)),
            "additionalProperties" to JsonPrimitive(false),
        ),
    )

private fun parameterSchema(
    parameter: Parameter,
    runtime: Runtime,
): JsonObject {
    val schema = typeSchema(parameter.type, runtime)
    val described = "${parameter.name}: ${parameter.help}" + (schema["description"]?.let { " ${(it as JsonPrimitive).content}" } ?: "")
    return JsonObject(schema + ("description" to JsonPrimitive(described)))
}

private fun typeSchema(
    type: ParameterType,
    runtime: Runtime,
): JsonObject =
    when (type) {
        is ParameterType.Value -> {
            valueSchema(type.kind)
        }

        ParameterType.Scene, ParameterType.Object, ParameterType.Property -> {
            schema("type" to JsonPrimitive("string"))
        }

        ParameterType.Component -> {
            componentSchema(runtime)
        }

        ParameterType.PropertyValue -> {
            schema(
                "anyOf" to
                    JsonArray(
                        listOf("number", "string", "boolean").map { schema("type" to JsonPrimitive(it)) },
                    ),
                "description" to JsonPrimitive("Colors are text such as #FFC107."),
            )
        }

        is ParameterType.Components -> {
            schema(
                "type" to JsonPrimitive("array"),
                "maxItems" to JsonPrimitive(type.maxSize),
                "items" to
                    schema(
                        "type" to JsonPrimitive("object"),
                        "properties" to
                            schema(
                                COMPONENT to componentSchema(runtime),
                                "values" to
                                    schema(
                                        "type" to JsonPrimitive("object"),
                                        "description" to JsonPrimitive("Property values by property ID; colors as text such as #FFC107."),
                                    ),
                            ),
                        "required" to JsonArray(listOf(JsonPrimitive(COMPONENT))),
                        "additionalProperties" to JsonPrimitive(false),
                    ),
            )
        }

        is ParameterType.OneOf -> {
            schema(
                "oneOf" to
                    JsonArray(
                        type.variants.map { variant ->
                            val examples = if (variant.examples.isEmpty()) "" else " Examples: ${variant.examples.joinToString("; ")}."
                            JsonObject(
                                objectSchema(
                                    variant.parameters,
                                    runtime,
                                    extra = mapOf(type.discriminator to schema("enum" to JsonArray(listOf(JsonPrimitive(variant.id))))),
                                    required = listOf(type.discriminator),
                                ) + (
                                    "description" to
                                        JsonPrimitive(
                                            "${variant.authoring.name}: ${variant.authoring.description}$examples",
                                        )
                                ),
                            )
                        },
                    ),
            )
        }

        is ParameterType.ListOf -> {
            schema(
                "type" to JsonPrimitive("array"),
                "maxItems" to JsonPrimitive(type.maxSize),
                "items" to typeSchema(type.item, runtime),
            )
        }
    }

private fun componentSchema(runtime: Runtime): JsonObject =
    schema(
        "type" to JsonPrimitive("string"),
        "enum" to JsonArray(runtime.catalog.map { JsonPrimitive(it.component.id) }),
    )

private fun valueSchema(kind: ValueKind<*>): JsonObject =
    when (kind) {
        is ValueKind.WholeNumber -> {
            schema(
                listOfNotNull(
                    "type" to JsonPrimitive("integer"),
                    kind.min.takeIf { it > -SAFE_INTEGER }?.let { "minimum" to JsonPrimitive(it) },
                    kind.max.takeIf { it < SAFE_INTEGER }?.let { "maximum" to JsonPrimitive(it) },
                    kind.unit.takeIf { it.isNotEmpty() }?.let { "description" to JsonPrimitive("In $it.") },
                ),
            )
        }

        is ValueKind.Decimal -> {
            schema(
                listOfNotNull(
                    "type" to JsonPrimitive("number"),
                    kind.min.takeIf { it > -SAFE_DECIMAL }?.let { "minimum" to JsonPrimitive(it) },
                    kind.max.takeIf { it < SAFE_DECIMAL }?.let { "maximum" to JsonPrimitive(it) },
                    kind.unit.takeIf { it.isNotEmpty() }?.let { "description" to JsonPrimitive("In $it.") },
                ),
            )
        }

        ValueKind.YesNo -> {
            schema("type" to JsonPrimitive("boolean"))
        }

        is ValueKind.Text -> {
            schema("type" to JsonPrimitive("string"), "maxLength" to JsonPrimitive(kind.maxLength))
        }

        is ValueKind.Choice -> {
            schema(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(kind.options.map { JsonPrimitive(it.id) }),
                "description" to JsonPrimitive("Options: ${kind.options.joinToString { "${it.id} (${it.label})" }}."),
            )
        }

        ValueKind.Color -> {
            schema(
                "type" to JsonPrimitive("string"),
                "pattern" to JsonPrimitive("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$"),
                "description" to JsonPrimitive("A color such as #FFC107."),
            )
        }

        is ValueKind.ObjectReference -> {
            schema("type" to JsonPrimitive("null"))
        }
    }

private fun schema(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

private fun schema(entries: List<Pair<String, JsonElement>>): JsonObject = JsonObject(linkedMapOf(*entries.toTypedArray()))

private const val SAFE_INTEGER = 9_007_199_254_740_991L
private const val SAFE_DECIMAL = 1e15
