package dev.spawnkit.runtime

/** A property and its current value, as shown by a generated inspector. */
public class InspectedProperty internal constructor(
    /** Property metadata, which determines the control to generate. */
    public val descriptor: PropertyDescriptor<*, *>,
    /** Current value; its Kotlin type matches the descriptor's value kind. */
    public val value: Any,
)

/** A described component attached to the inspected object. */
public class InspectedComponent internal constructor(
    /** Component metadata. */
    public val descriptor: ComponentDescriptor<*>,
    /** Visible properties with current values, in declaration order. */
    public val properties: List<InspectedProperty>,
)

/** Outcome of an authoring edit. Rejections leave the world unchanged. */
public sealed interface EditResult {
    /** The edit was applied; [previous] restores it (for undo), or is `null` when nothing existed before. */
    public data class Applied(
        /** Value before the edit. */
        public val previous: Any?,
    ) : EditResult

    /** The edit was refused with a plain-language [message] naming the problem. */
    public data class Rejected(
        /** Explanation suitable for the creator. */
        public val message: String,
    ) : EditResult
}

/**
 * Lists the described components of [entity] with current property values: a generic, headless inspector model.
 * Components appear in catalog (ID) order and properties in declaration order. [visibility] [Visibility.Basic]
 * hides advanced components and properties. Undescribed components are omitted rather than guessed.
 */
public fun Runtime.inspect(
    world: World,
    entity: EntityHandle,
    visibility: Visibility = Visibility.Advanced,
): List<InspectedComponent> {
    require(owns(world)) { "World belongs to another runtime" }
    if (!world.isAlive(entity)) return emptyList()
    return catalog.mapNotNull { descriptor -> inspectComponent(world, entity, descriptor, visibility) }
}

private fun <T : Any> inspectComponent(
    world: World,
    entity: EntityHandle,
    descriptor: ComponentDescriptor<T>,
    visibility: Visibility,
): InspectedComponent? {
    if (visibility == Visibility.Basic && descriptor.visibility == Visibility.Advanced) return null
    val value = world.get(entity, descriptor.component) ?: return null
    val properties =
        descriptor.properties
            .filter { visibility == Visibility.Advanced || it.visibility == Visibility.Basic }
            .map { InspectedProperty(it, it.get(value)) }
    return InspectedComponent(descriptor, properties)
}

/**
 * Validates and applies a typed property edit outside a tick. Stale objects, missing components and values the
 * property's [ValueKind] rejects produce [EditResult.Rejected] without changing anything.
 */
public fun <T : Any, V : Any> Runtime.edit(
    world: World,
    entity: EntityHandle,
    property: PropertyDescriptor<T, V>,
    value: V,
): EditResult {
    require(owns(world)) { "World belongs to another runtime" }
    val descriptor =
        catalog.firstOrNull { candidate -> candidate.properties.any { it === property } }
            ?: return EditResult.Rejected("That property is not installed in this project")

    @Suppress("UNCHECKED_CAST") // The descriptor that owns `property` describes ComponentType<T>.
    val component = descriptor.component as ComponentType<T>
    if (!world.isAlive(entity)) return EditResult.Rejected("That object no longer exists")
    val current = world.get(entity, component) ?: return EditResult.Rejected("The object has no ${component.authoring.name}")
    property.kind.problem(value, world)?.let { return EditResult.Rejected("${property.authoring.name}: $it") }
    val previous = property.get(current)
    world.set(entity, component, property.with(current, value))
    return EditResult.Applied(previous)
}

/**
 * Untyped variant of [edit] for editors and agent tools that address properties by IDs. A value of the wrong
 * Kotlin type for the property's kind is rejected rather than cast.
 */
public fun Runtime.edit(
    world: World,
    entity: EntityHandle,
    componentId: String,
    propertyId: String,
    value: Any,
): EditResult {
    val descriptor =
        catalog.firstOrNull { it.component.id == componentId }
            ?: return EditResult.Rejected("No installed component is called '$componentId'")
    val property =
        descriptor.property(propertyId) ?: return EditResult.Rejected("${descriptor.component.authoring.name} has no '$propertyId'")
    return editErased(world, entity, property, value)
}

private fun <T : Any, V : Any> Runtime.editErased(
    world: World,
    entity: EntityHandle,
    property: PropertyDescriptor<T, V>,
    value: Any,
): EditResult {
    if (!property.kind.accepts(value)) return EditResult.Rejected("${property.authoring.name} needs a different kind of value")
    @Suppress("UNCHECKED_CAST") // Checked by ValueKind.accepts above.
    return edit(world, entity, property, value as V)
}

/**
 * Attaches a fresh default value of a described component, outside a tick. Returns [EditResult.Applied] with a
 * `null` previous value, or a rejection when the object is stale, already has the component, or the component is
 * not described by this runtime.
 */
public fun <T : Any> Runtime.addComponent(
    world: World,
    entity: EntityHandle,
    component: ComponentType<T>,
): EditResult {
    require(owns(world)) { "World belongs to another runtime" }
    val descriptor = descriptor(component) ?: return EditResult.Rejected("${component.authoring.name} cannot be added from the editor")
    if (!world.isAlive(entity)) return EditResult.Rejected("That object no longer exists")
    if (world.get(entity, component) != null) return EditResult.Rejected("The object already has ${component.authoring.name}")
    world.set(entity, component, descriptor.create())
    return EditResult.Applied(null)
}

/** Live objects that may be chosen for [kind], in slot order: the candidate list of a generated object picker. */
public fun Runtime.referenceCandidates(
    world: World,
    kind: ValueKind.ObjectReference,
): List<EntityHandle> {
    require(owns(world)) { "World belongs to another runtime" }
    return world.entities(*kind.requires.toTypedArray())
}
