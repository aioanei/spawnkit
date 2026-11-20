package dev.spawnkit.runtime

/** Progressive disclosure level of an authoring element. Basic views hide [Advanced] elements. */
public enum class Visibility {
    /** Shown to every creator. */
    Basic,

    /** Shown only when advanced options are requested. */
    Advanced,
}

/** One selectable option of a [ValueKind.Choice]. */
public data class ChoiceOption(
    /** Stable stored value. */
    public val id: String,
    /** Friendly label. */
    public val label: String,
)

/** An optional reference to an object (entity) in the same world; `null` means "not set". */
public data class ObjectRef(
    /** Referenced entity, or `null`. */
    public val entity: EntityHandle?,
)

/**
 * The editing semantics of a property value: what control an editor generates and which values are valid.
 * Kinds are presentation-independent; a Compose inspector, an agent tool or a test can all use them.
 */
public sealed interface ValueKind<V : Any> {
    /** Checks [value] in [world]; returns a plain-language problem, or `null` when valid. */
    public fun problem(
        value: V,
        world: World?,
    ): String?

    /**
     * Converts untyped editor input to this kind's Kotlin value type, or returns `null` when it has another type.
     * Conversions are identical on every target (decimals accept any `Int`, `Float` or `Double` input).
     */
    public fun coerce(value: Any): V?

    /** A whole number between [min] and [max], adjusted in [step]s, displayed with [unit]. */
    public data class WholeNumber(
        /** Smallest allowed value. */
        public val min: Long = Long.MIN_VALUE,
        /** Largest allowed value. */
        public val max: Long = Long.MAX_VALUE,
        /** Suggested increment for steppers and sliders. */
        public val step: Long = 1,
        /** Display unit, for example "px" or "points". */
        public val unit: String = "",
    ) : ValueKind<Long> {
        init {
            require(min <= max && step > 0) { "Invalid whole-number range" }
        }

        override fun problem(
            value: Long,
            world: World?,
        ): String? = if (value < min || value > max) "Choose a whole number from $min to $max" else null

        override fun coerce(value: Any): Long? = value as? Long
    }

    /** A finite decimal between [min] and [max], displayed with [unit]. */
    public data class Decimal(
        /** Smallest allowed value. */
        public val min: Double = -Double.MAX_VALUE,
        /** Largest allowed value. */
        public val max: Double = Double.MAX_VALUE,
        /** Display unit. */
        public val unit: String = "",
    ) : ValueKind<Double> {
        init {
            require(min.isFinite() && max.isFinite() && min <= max) { "Invalid decimal range" }
        }

        override fun problem(
            value: Double,
            world: World?,
        ): String? = if (!value.isFinite() || value < min || value > max) "Choose a number from $min to $max" else null

        override fun coerce(value: Any): Double? =
            when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Int -> value.toDouble()
                else -> null
            }
    }

    /** A yes/no switch. */
    public data object YesNo : ValueKind<Boolean> {
        override fun problem(
            value: Boolean,
            world: World?,
        ): String? = null

        override fun coerce(value: Any): Boolean? = value as? Boolean
    }

    /** Text up to [maxLength] characters; [multiline] suggests a larger editor. */
    public data class Text(
        /** Maximum characters. */
        public val maxLength: Int = 1_000,
        /** Whether line breaks are expected. */
        public val multiline: Boolean = false,
    ) : ValueKind<String> {
        init {
            require(maxLength > 0) { "Text length limit must be positive" }
        }

        override fun problem(
            value: String,
            world: World?,
        ): String? =
            when {
                value.length > maxLength -> "Use at most $maxLength characters"
                !multiline && ('\n' in value || '\r' in value) -> "This text must fit on one line"
                else -> null
            }

        override fun coerce(value: Any): String? = value as? String
    }

    /** One of [options], stored by option ID. */
    public data class Choice(
        /** Available options, in display order. */
        public val options: List<ChoiceOption>,
    ) : ValueKind<String> {
        init {
            require(options.isNotEmpty()) { "A choice needs at least one option" }
            require(options.map { it.id }.toSet().size == options.size) { "Choice option IDs must be unique" }
        }

        override fun problem(
            value: String,
            world: World?,
        ): String? = if (options.none { it.id == value }) "Choose one of: ${options.joinToString { it.label }}" else null

        override fun coerce(value: Any): String? = value as? String
    }

    /**
     * An optional object reference. Pickers offer live objects having every component in [requires].
     * Validation needs the world; without one, only "not set" is checked.
     */
    public data class ObjectReference(
        /** Components the referenced object must have. */
        public val requires: List<ComponentType<*>> = emptyList(),
    ) : ValueKind<ObjectRef> {
        override fun problem(
            value: ObjectRef,
            world: World?,
        ): String? {
            val entity = value.entity ?: return null
            if (world == null) return "Objects can only be chosen inside a scene"
            if (!world.isAlive(entity)) return "That object no longer exists"
            val missing = requires.firstOrNull { world.get(entity, it) == null } ?: return null
            return "Choose an object that has ${missing.authoring.name}"
        }

        override fun coerce(value: Any): ObjectRef? = value as? ObjectRef
    }
}

/**
 * Authoring metadata plus typed access for one property of component type [T]. Executable code uses [T]
 * directly; editors and agents use this descriptor. [read] and [write] must be pure; [write] returns an updated
 * component value (it may mutate and return the same instance for mutable components).
 */
public class PropertyDescriptor<T : Any, V : Any> internal constructor(
    /** Property identifier, unique within its component. */
    public val id: String,
    /** Value kind, which also determines the generated control. */
    public val kind: ValueKind<V>,
    /** Friendly name, explanation and grouping. */
    public val authoring: AuthoringInfo,
    /** Value used for new components and "reset". */
    public val default: V,
    /** Disclosure level. */
    public val visibility: Visibility,
    /** Short example values or uses shown as help. */
    public val examples: List<String>,
    private val read: (T) -> V,
    private val write: (T, V) -> T,
) {
    /** Reads this property from a component value. */
    public fun get(component: T): V = read(component)

    internal fun with(
        component: T,
        value: V,
    ): T = write(component, value)
}

/** All authoring metadata of one component type. */
public class ComponentDescriptor<T : Any> internal constructor(
    /** Described component. */
    public val component: ComponentType<T>,
    /** Properties in display order. */
    public val properties: List<PropertyDescriptor<T, *>>,
    /** Disclosure level of the whole component. */
    public val visibility: Visibility,
    private val factory: () -> T,
) {
    /** Creates a fresh component value with every property at its default; used by "Add component". */
    public fun create(): T = factory()

    /** Finds a property by [id]. */
    public fun property(id: String): PropertyDescriptor<T, *>? = properties.firstOrNull { it.id == id }
}

/** Collects the descriptor of one component; see [RuntimeBuilder.describe]. */
public class ComponentDescriptorBuilder<T : Any> internal constructor(
    private val component: ComponentType<T>,
) {
    private val properties = mutableListOf<PropertyDescriptor<T, *>>()
    private var factory: (() -> T)? = null

    /** Disclosure level of the whole component. */
    public var visibility: Visibility = Visibility.Basic

    /** Sets the factory for fresh component values. It must return a new instance on every call. */
    public fun create(factory: () -> T) {
        this.factory = factory
    }

    /** Describes a property with typed accessors. Its [default] must be valid for [kind]. */
    public fun <V : Any> property(
        id: String,
        kind: ValueKind<V>,
        authoring: AuthoringInfo,
        default: V,
        read: (T) -> V,
        write: (T, V) -> T,
        visibility: Visibility = Visibility.Basic,
        examples: List<String> = emptyList(),
    ): PropertyDescriptor<T, V> {
        require(id.matches(Regex("[a-z][A-Za-z0-9_]*"))) { "Invalid property ID '$id'" }
        require(properties.none { it.id == id }) { "Duplicate property '$id' on '${component.id}'" }
        require(kind.problem(default, null) == null) { "Default of '$id' is invalid: ${kind.problem(default, null)}" }
        return PropertyDescriptor(id, kind, authoring, default, visibility, examples.toList(), read, write).also { properties += it }
    }

    internal fun build(): ComponentDescriptor<T> {
        val create = requireNotNull(factory) { "Component '${component.id}' descriptor needs a create { } factory" }
        val sample = create()
        for (property in properties) checkDefault(property, sample)
        return ComponentDescriptor(component, properties.toList(), visibility, create)
    }
}

private fun <T : Any, V : Any> checkDefault(
    property: PropertyDescriptor<T, V>,
    sample: T,
) {
    val value = property.get(sample)
    require(value == property.default) {
        "The create { } factory must start '${property.id}' at its default ${property.default}, not $value"
    }
}
