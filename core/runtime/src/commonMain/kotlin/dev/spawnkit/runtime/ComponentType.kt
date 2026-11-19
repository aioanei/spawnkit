package dev.spawnkit.runtime

/** Basic human-facing metadata; property controls and richer authoring descriptors follow in later gates. */
public data class AuthoringInfo(
    /** Name shown in the editor, independent of the technical type ID. */
    public val name: String,
    /** Optional plain-language explanation. */
    public val description: String = "",
    /** Palette grouping for the capability. */
    public val category: String = "General",
) {
    init {
        require(name.isNotBlank()) { "An authoring name must not be blank" }
        require(category.isNotBlank()) { "An authoring category must not be blank" }
    }
}

/**
 * Typed component identity registered by [RuntimeBuilder]. Identity is scoped to that builder's runtime;
 * another runtime's type with the same [id] is not interchangeable. No serializer or closed subtype list is required.
 */
public class ComponentType<T : Any> internal constructor(
    /** Stable namespaced extension identifier, for example `example:health`. */
    public val id: String,
    /** Human-facing metadata for discovery and future authoring tools. */
    public val authoring: AuthoringInfo,
    internal val registry: Any,
)

internal fun requireCapabilityId(id: String) {
    require(id.matches(Regex("[a-z][a-z0-9_.-]*:[a-z][a-z0-9_.-]*"))) {
        "Expected a namespaced capability ID, got '$id'"
    }
}
