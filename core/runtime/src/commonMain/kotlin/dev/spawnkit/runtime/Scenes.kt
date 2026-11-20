package dev.spawnkit.runtime

/** A typed factory that creates a fresh component value for each instantiated object. */
public class ComponentSeed<T : Any>(
    /** Component to attach. */
    public val component: ComponentType<T>,
    /** Creates a new value per instance; must not return shared mutable state. */
    public val create: () -> T,
)

/** One authored object of a [SceneTemplate]. */
public class TemplateObject(
    /** Stable authoring identifier, unique within the template. */
    public val id: String,
    /** Authoring ID of the parent object, or `null` for a template root. */
    public val parent: String? = null,
    /** Components to attach, in order. */
    public val components: List<ComponentSeed<*>> = emptyList(),
)

/**
 * A reusable arrangement of objects (a scene or prefab) that can be instantiated any number of times. Objects are
 * created in declaration order; the template's hierarchy is reproduced, keeping declaration order among siblings.
 */
public class SceneTemplate(
    /** Stable template identifier. */
    public val id: String,
    objects: List<TemplateObject>,
) {
    /** Objects in declaration order; a private copy. */
    public val objects: List<TemplateObject> = objects.toList()

    init {
        require(id.isNotBlank()) { "A scene template needs an ID" }
        val ids = this.objects.map { it.id }
        require(ids.all { it.isNotBlank() }) { "Template objects need IDs" }
        require(ids.toSet().size == ids.size) { "Template object IDs must be unique in '$id'" }
        val parents = this.objects.associate { it.id to it.parent }
        for (obj in this.objects) {
            obj.parent?.let { require(it in parents) { "Object '${obj.id}' has unknown parent '$it'" } }
            var cursor = obj.parent
            var steps = 0
            while (cursor != null) {
                require(cursor != obj.id && steps++ <= ids.size) { "Object '${obj.id}' is inside itself" }
                cursor = parents[cursor]
            }
        }
    }
}

/** Where an instantiated entity came from: which template, which instance of it, and which authored object. */
public data class ObjectIdentity(
    /** Template identifier. */
    public val templateId: String,
    /** Per-world instance number, starting at 1. */
    public val instance: Long,
    /** Authoring object identifier. */
    public val objectId: String,
)

/** The result of instantiating a template: authoring IDs mapped to this instance's runtime handles. */
public class SceneInstance internal constructor(
    /** Instantiated template. */
    public val template: SceneTemplate,
    /** Per-world instance number. */
    public val number: Long,
    private val handles: Map<String, EntityHandle>,
) {
    /** The handle created for authoring object [objectId], or `null` when the template has no such object. */
    public fun entity(objectId: String): EntityHandle? = handles[objectId]

    /** Handles of the template's root objects, in declaration order. */
    public val roots: List<EntityHandle>
        get() = template.objects.filter { it.parent == null }.map { handles.getValue(it.id) }
}
