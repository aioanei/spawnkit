package dev.spawnkit.editing

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.ValueKind

/**
 * One argument of a catalog entry: an editor operation ([OperationSpec]) or a rule block. The same metadata generates
 * the builder's controls and the agent tools, so each capability is described once (ADR-0009).
 */
public class Parameter(
    /** Argument key, unique within its entry. Agent tools use the same key. */
    public val id: String,
    /** Friendly name shown next to the control. */
    public val name: String,
    /** Plain-language explanation, shown as help and given to agents. */
    public val help: String,
    /** Accepted values; this also decides the generated control. */
    public val type: ParameterType,
    /** Value controls start with and that is used when the argument is omitted, or `null` for none. */
    public val default: Any? = null,
    /**
     * Whether callers must give the argument. When it is not required and has no [default], omitting it means "none",
     * for example no parent object.
     */
    public val required: Boolean = default == null,
) {
    init {
        require(id.matches(ARGUMENT_ID)) { "Invalid parameter ID '$id'" }
        require(name.isNotBlank() && help.isNotBlank()) { "Parameter '$id' needs a name and help" }
    }
}

/** The shape of a [Parameter]'s values, independent of how a builder screen or an agent presents them. */
public sealed interface ParameterType {
    /**
     * A plain value checked by a descriptor value [kind] (whole number, decimal, yes/no, text, choice or color).
     * [suggestions] names a source of typical values that pickers offer, for example [Suggestions.TAGS].
     */
    public data class Value(
        /** Value kind; object references are not accepted here. */
        public val kind: ValueKind<*>,
        /** Name of a suggestion source, or `null`. */
        public val suggestions: String? = null,
    ) : ParameterType {
        init {
            require(kind !is ValueKind.ObjectReference) { "Object references are not plain parameter values" }
        }
    }

    /** A scene of the project, by ID. */
    public data object Scene : ParameterType

    /** An object, by ID, in the scene given by the entry's `scene` argument. */
    public data object Object : ParameterType

    /** An installed component, by ID. */
    public data object Component : ParameterType

    /** A property, by ID, of the component given by the entry's `component` argument. */
    public data object Property : ParameterType

    /** A value for the property named by the `component` and `property` arguments, checked by that property's kind. */
    public data object PropertyValue : ParameterType

    /**
     * Installed components with optional property values, as the palette adds them. Each item is a [ComponentDoc]
     * or a map with a `component` ID and an optional `values` map. Normalized values are [ComponentDoc]s.
     */
    public data class Components(
        /** Largest number of components. */
        public val maxSize: Int = 32,
    ) : ParameterType

    /**
     * One of several [variants], each with its own parameters. Values are [Picked]. JSON tools name the chosen variant
     * in the [discriminator] property.
     */
    public data class OneOf(
        /** JSON property that names the variant, for example `block`. */
        public val discriminator: String,
        /** Alternatives in display order. */
        public val variants: List<Variant>,
    ) : ParameterType {
        init {
            require(variants.isNotEmpty()) { "A choice of variants needs at least one variant" }
            require(variants.map { it.id }.toSet().size == variants.size) { "Variant IDs must be unique" }
            require(variants.none { v -> v.parameters.any { it.id == discriminator } }) { "'$discriminator' is used by a variant" }
        }

        /** The variant with [id], or `null`. */
        public fun variant(id: String): Variant? = variants.firstOrNull { it.id == id }
    }

    /** Up to [maxSize] values of type [item], in order. */
    public data class ListOf(
        /** Type of each element. */
        public val item: ParameterType,
        /** Largest number of elements. */
        public val maxSize: Int,
    ) : ParameterType
}

/** One alternative of a [ParameterType.OneOf], for example a rule block, with its own parameters. */
public class Variant(
    /** Stable ID, also used by agents and in stored choices. */
    public val id: String,
    /** Friendly name, plain-language explanation and grouping. */
    public val authoring: AuthoringInfo,
    /** Arguments of this alternative. */
    public val parameters: List<Parameter> = emptyList(),
    /** Short example uses shown as help and given to agents. */
    public val examples: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "A variant needs an ID" }
        require(parameters.map { it.id }.toSet().size == parameters.size) { "Duplicate parameter in variant '$id'" }
    }
}

/** A chosen [Variant] of a [ParameterType.OneOf] with its arguments. */
public data class Picked(
    /** ID of the chosen variant. */
    public val variant: String,
    /** Arguments of the variant, by parameter ID. */
    public val arguments: Map<String, Any?> = emptyMap(),
)

/** Well-known suggestion sources for [ParameterType.Value.suggestions]. */
public object Suggestions {
    /** Tags used by objects in the current scene. */
    public const val TAGS: String = "tags"

    /** Scenes of the project, offered by name; the value is the scene ID. */
    public const val SCENES: String = "scenes"

    /** Images of the project, offered by name; the value is the image ID. */
    public const val IMAGES: String = "images"
}

/** The project and installed capabilities that catalog arguments are checked against. */
public class CatalogContext(
    /** Current project. */
    public val project: Project,
    /** Runtime whose installed descriptors define what exists. */
    public val runtime: Runtime,
)

/** Outcome of [checkArguments]. */
public sealed interface CheckedArguments {
    /** Arguments are valid; [values] holds normalized values with defaults filled in. */
    public data class Valid(
        /** Normalized values by parameter ID. */
        public val values: Map<String, Any?>,
    ) : CheckedArguments

    /** Arguments are not valid, with a plain-language explanation. */
    public data class Invalid(
        /** What is wrong, naming the argument. */
        public val message: String,
    ) : CheckedArguments
}

/**
 * Checks [arguments] against [parameters] exactly as the builder and agent tools do, and normalizes them. Whole
 * numbers become `Long`, decimals `Double`, colors given as `#RRGGBB` text become ARGB `Long`s, choices given by
 * label become option IDs, components become [ComponentDoc]s and variants become [Picked].
 *
 * References (scenes, objects, components and properties) need a [context].
 */
public fun checkArguments(
    parameters: List<Parameter>,
    arguments: Map<String, Any?>,
    context: CatalogContext? = null,
): CheckedArguments =
    try {
        CheckedArguments.Valid(ArgumentChecker(context).check(parameters, arguments))
    } catch (problem: ArgumentProblem) {
        CheckedArguments.Invalid(problem.message ?: "These values are not possible")
    }

internal class ArgumentProblem(
    message: String,
) : Exception(message)

private fun problem(message: String): Nothing = throw ArgumentProblem(message)

private class ArgumentChecker(
    private val context: CatalogContext?,
) {
    fun check(
        parameters: List<Parameter>,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> {
        val unknown = arguments.keys.firstOrNull { key -> parameters.none { it.id == key } }
        if (unknown != null) problem("There is no setting called '$unknown'. Use: ${parameters.joinToString { it.id }}")
        val values = LinkedHashMap<String, Any?>()
        for (parameter in parameters) {
            val given = arguments[parameter.id]
            values[parameter.id] =
                when {
                    given != null -> value(parameter, parameter.type, given, values)
                    parameter.required -> problem("Give a value for ${parameter.name}")
                    else -> parameter.default?.let { value(parameter, parameter.type, it, values) }
                }
        }
        return values
    }

    private fun value(
        parameter: Parameter,
        type: ParameterType,
        given: Any,
        siblings: Map<String, Any?>,
    ): Any =
        when (type) {
            is ParameterType.Value -> plain(parameter.name, type.kind, given)
            ParameterType.Scene -> scene(parameter, given).id
            ParameterType.Object -> obj(parameter, given, siblings)
            ParameterType.Component -> component(parameter.name, given).component.id
            ParameterType.Property -> property(parameter, given, siblings)
            ParameterType.PropertyValue -> propertyValue(parameter, given, siblings)
            is ParameterType.Components -> components(parameter, type, given)
            is ParameterType.OneOf -> picked(parameter, type, given)
            is ParameterType.ListOf -> list(parameter, type, given, siblings)
        }

    private fun requireContext(name: String): CatalogContext = context ?: problem("$name can only be chosen inside a project")

    private fun text(
        name: String,
        given: Any,
    ): String = given as? String ?: problem("$name needs a name or ID as text")

    private fun scene(
        parameter: Parameter,
        given: Any,
    ): SceneDoc {
        val id = text(parameter.name, given)
        return requireContext(parameter.name).project.scene(id) ?: problem("There is no scene '$id'")
    }

    private fun obj(
        parameter: Parameter,
        given: Any,
        siblings: Map<String, Any?>,
    ): String {
        val id = text(parameter.name, given)
        val sceneId = siblings[SCENE] as? String ?: problem("Choose the scene before ${parameter.name}")
        val scene = requireContext(parameter.name).project.scene(sceneId) ?: problem("There is no scene '$sceneId'")
        return scene.find(id)?.id ?: problem("There is no object '$id' in ${scene.name}")
    }

    private fun component(
        name: String,
        given: Any,
    ): ComponentDescriptor<*> {
        val id = text(name, given)
        return requireContext(name).runtime.catalog.firstOrNull { it.component.id == id }
            ?: problem("No installed component is called '$id'")
    }

    private fun property(
        parameter: Parameter,
        given: Any,
        siblings: Map<String, Any?>,
    ): String {
        val id = text(parameter.name, given)
        val descriptor = component(parameter.name, siblings[COMPONENT] ?: problem("Choose the component before ${parameter.name}"))
        return descriptor.property(id)?.id ?: problem("${descriptor.component.authoring.name} has no '$id'")
    }

    private fun propertyValue(
        parameter: Parameter,
        given: Any,
        siblings: Map<String, Any?>,
    ): Any {
        val descriptor = component(parameter.name, siblings[COMPONENT] ?: problem("Choose the component before ${parameter.name}"))
        val propertyId = siblings[PROPERTY] as? String ?: problem("Choose the property before ${parameter.name}")
        val property = descriptor.property(propertyId) ?: problem("${descriptor.component.authoring.name} has no '$propertyId'")
        return plain(property.authoring.name, property.kind, given)
    }

    private fun components(
        parameter: Parameter,
        type: ParameterType.Components,
        given: Any,
    ): List<ComponentDoc> {
        val items = given as? List<*> ?: problem("${parameter.name} needs a list of components")
        if (items.size > type.maxSize) problem("${parameter.name}: use at most ${type.maxSize} components")
        val docs =
            items.map { item ->
                val (id, values) =
                    when (item) {
                        is ComponentDoc -> item.componentId to item.values
                        is Map<*, *> -> componentEntry(parameter.name, item)
                        else -> problem("${parameter.name} needs components, each with a component ID")
                    }
                val descriptor = component(parameter.name, id)
                ComponentDoc(
                    descriptor.component.id,
                    values.mapValues { (propertyId, value) ->
                        val property =
                            descriptor.property(propertyId)
                                ?: problem("${descriptor.component.authoring.name} has no '$propertyId'")
                        plain(property.authoring.name, property.kind, value ?: problem("Give a value for ${property.authoring.name}"))
                    },
                )
            }
        if (docs.map { it.componentId }.toSet().size != docs.size) problem("An object cannot have the same component twice")
        return docs
    }

    private fun componentEntry(
        name: String,
        item: Map<*, *>,
    ): Pair<String, Map<String, Any?>> {
        val unknown = item.keys.firstOrNull { it != COMPONENT && it != VALUES }
        if (unknown != null) problem("$name: a component has '$COMPONENT' and '$VALUES', not '$unknown'")
        val id = item[COMPONENT] as? String ?: problem("$name: each component needs a '$COMPONENT' ID")
        val values = item[VALUES] ?: emptyMap<String, Any?>()
        if (values !is Map<*, *>) problem("$name: '$VALUES' must map property IDs to values")
        return id to values.entries.associate { (key, value) -> (key as? String ?: problem("$name: property IDs are text")) to value }
    }

    private fun picked(
        parameter: Parameter,
        type: ParameterType.OneOf,
        given: Any,
    ): Picked {
        val picked =
            when (given) {
                is Picked -> given
                is String -> Picked(given)
                else -> problem("${parameter.name} needs one of: ${type.variants.joinToString { it.id }}")
            }
        val variant =
            type.variant(picked.variant)
                ?: type.variants.firstOrNull { it.authoring.name.equals(picked.variant, ignoreCase = true) }
                ?: problem("${parameter.name}: '${picked.variant}' is not one of ${type.variants.joinToString { it.id }}")
        val nested =
            try {
                check(variant.parameters, picked.arguments)
            } catch (inner: ArgumentProblem) {
                problem("${variant.authoring.name}: ${inner.message}")
            }
        return Picked(variant.id, nested)
    }

    private fun list(
        parameter: Parameter,
        type: ParameterType.ListOf,
        given: Any,
        siblings: Map<String, Any?>,
    ): List<Any> {
        val items = given as? List<*> ?: problem("${parameter.name} needs a list")
        if (items.size > type.maxSize) problem("${parameter.name}: use at most ${type.maxSize}")
        return items.map { value(parameter, type.item, it ?: problem("${parameter.name} cannot contain empty entries"), siblings) }
    }

    /** Normalizes [given] for [kind] and checks it, naming the setting [name] in problems. */
    fun plain(
        name: String,
        kind: ValueKind<*>,
        given: Any,
    ): Any {
        val normalized =
            when (kind) {
                is ValueKind.WholeNumber -> {
                    when (given) {
                        is Int -> given.toLong()
                        is Double -> if (given.isFinite() && given == kotlin.math.floor(given)) given.toLong() else given
                        else -> given
                    }
                }

                is ValueKind.Decimal -> {
                    when (given) {
                        is Long -> given.toDouble()
                        is Int -> given.toDouble()
                        else -> given
                    }
                }

                ValueKind.Color -> {
                    (given as? String)?.let(::hexColor) ?: given
                }

                is ValueKind.Choice -> {
                    (given as? String)?.let { text ->
                        kind.options.firstOrNull { it.id == text }?.id
                            ?: kind.options.firstOrNull { it.label.equals(text, ignoreCase = true) }?.id
                    } ?: given
                }

                else -> {
                    given
                }
            }
        return checkKind(name, kind, normalized)
    }

    private fun <V : Any> checkKind(
        name: String,
        kind: ValueKind<V>,
        value: Any,
    ): V {
        if (kind is ValueKind.ObjectReference) problem("$name cannot be chosen yet")
        val coerced = kind.coerce(value) ?: problem("$name needs ${expected(kind)}")
        kind.problem(coerced, null)?.let { problem("$name: $it") }
        return coerced
    }
}

private fun expected(kind: ValueKind<*>): String =
    when (kind) {
        is ValueKind.WholeNumber -> "a whole number"
        is ValueKind.Decimal -> "a number"
        ValueKind.YesNo -> "yes or no"
        is ValueKind.Text -> "text"
        is ValueKind.Choice -> "one of: ${kind.options.joinToString { it.id }}"
        ValueKind.Color -> "a color such as #FFC107"
        is ValueKind.ObjectReference -> "an object"
    }

/** Parses `#RRGGBB` (opaque) or `#AARRGGBB` into an ARGB value, or returns `null`. */
internal fun hexColor(text: String): Long? {
    if (!text.startsWith('#')) return null
    val digits = text.drop(1)
    if (digits.length != 6 && digits.length != 8) return null
    if (digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    val value = digits.toLong(16)
    return if (digits.length == 6) value or 0xFF000000L else value
}

/** Argument key of the scene that object arguments belong to. */
internal const val SCENE = "scene"

/** Argument key of the component that property arguments belong to. */
internal const val COMPONENT = "component"

/** Argument key of the property that property values belong to. */
internal const val PROPERTY = "property"

private const val VALUES = "values"

private val ARGUMENT_ID = Regex("[a-z][a-z0-9_]*")
