package dev.spawnkit.editing

/**
 * Authored values of one component on an object, keyed by descriptor property ID. Properties not listed use the
 * descriptor's default. Values have the Kotlin type of the property's value kind (`Long`, `Double`, `Boolean`,
 * `String`); they are validated by every operation that sets them.
 */
public data class ComponentDoc(
    /** Installed component ID, for example `graphics:shape`. */
    public val componentId: String,
    /** Explicitly authored property values. */
    public val values: Map<String, Any> = emptyMap(),
)

/** An authored object in a scene. [id] is stable across edits, saves and play sessions. */
public data class ObjectDoc(
    /** Stable authoring identifier, unique within the project. */
    public val id: String,
    /** Creator-facing name. */
    public val name: String,
    /** Parent object ID, or `null` for a scene root. */
    public val parent: String? = null,
    /** Components in the order they were added. */
    public val components: List<ComponentDoc> = emptyList(),
) {
    /** The authored values of [componentId], or `null` when the object lacks it. */
    public fun component(componentId: String): ComponentDoc? = components.firstOrNull { it.componentId == componentId }
}

/** An authored scene: objects in authoring order (siblings keep this order). */
public data class SceneDoc(
    /** Stable scene identifier. */
    public val id: String,
    /** Creator-facing name. */
    public val name: String,
    /** Objects in authoring order. */
    public val objects: List<ObjectDoc> = emptyList(),
) {
    /** The object with [id], or `null`. */
    public fun find(id: String): ObjectDoc? = objects.firstOrNull { it.id == id }

    /** Direct children of [parent] (`null` = roots), in authoring order. */
    public fun childrenOf(parent: String?): List<ObjectDoc> = objects.filter { it.parent == parent }

    /** [id] and all its descendants, depth-first in authoring order. */
    public fun subtree(id: String): List<String> = listOf(id) + childrenOf(id).flatMap { subtree(it.id) }
}

/**
 * An editable project. It is a meaningful object model even when never saved; persistence encodes it at a boundary
 * (C5). Immutable: every operation produces a new project, which makes undo, autosave and AI staging simple.
 */
public data class Project(
    /** Creator-facing project name. */
    public val name: String,
    /** Scenes in order; the first is the entry scene. */
    public val scenes: List<SceneDoc>,
    /** Counter used to mint unique object and scene IDs. */
    public val nextId: Long = 1,
) {
    /** The scene with [id], or `null`. */
    public fun scene(id: String): SceneDoc? = scenes.firstOrNull { it.id == id }

    /** Factories. */
    public companion object {
        /** A functional blank project with one empty scene. */
        public fun blank(name: String = "My game"): Project = Project(name, listOf(SceneDoc("scene-1", "Main scene")), nextId = 2)
    }
}
