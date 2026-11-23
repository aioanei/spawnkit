package dev.spawnkit.editing

import dev.spawnkit.program.Handler

/**
 * Authored values of one component on an object, keyed by descriptor property ID. Properties not listed use the
 * descriptor's default. Values have the Kotlin type of the property's value kind (`Long`, `Double`, `Boolean`,
 * `String`); they are validated by every operation that sets them. Persistence can also retain opaque JSON values
 * for unavailable capabilities; these are preserved, and execution is blocked until the matching capability exists.
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
    /**
     * Rules that run on this object while playing, as `core/program` handlers. Visual rules, advanced tools and agents
     * all edit this one executable model. Rules may have problems while being authored; worlds report and skip them.
     */
    public val rules: List<Handler> = emptyList(),
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
    /** Counter used to mint unique object, scene and image IDs. */
    public val nextId: Long = 1,
    /** PNG images that sprites can show (ADR-0012), in import order. */
    public val images: List<ImageAsset> = emptyList(),
) {
    /** The image with [id], or `null`. */
    public fun image(id: String): ImageAsset? = images.firstOrNull { it.id == id }

    /** The scene with [id], or `null`. */
    public fun scene(id: String): SceneDoc? = scenes.firstOrNull { it.id == id }

    /** Factories. */
    public companion object {
        /** A functional blank project with one empty scene. */
        public fun blank(name: String = "My game"): Project = Project(name, listOf(SceneDoc("scene-1", "Main scene")), nextId = 2)
    }
}

/**
 * Structural problems that make a project unusable: duplicate scene, object or component IDs, unknown parents and
 * parent cycles. Empty for every project produced by editor operations; checked when a project is loaded.
 */
public fun Project.problems(): List<String> {
    val problems = mutableListOf<String>()
    if (scenes.isEmpty()) problems += "The project has no scenes"
    val sceneIds = scenes.map { it.id }
    if (sceneIds.toSet().size != sceneIds.size) problems += "Two scenes share an ID"
    val objectIds = scenes.flatMap { scene -> scene.objects.map { it.id } }
    if (objectIds.toSet().size != objectIds.size) problems += "Two objects share an ID"
    val imageIds = images.map { it.id }
    if (imageIds.toSet().size != imageIds.size) problems += "Two images share an ID"
    if (images.any { it.id.isBlank() || it.name.isBlank() }) problems += "Every image needs a name and an identifier"
    if (images.size > ProjectImages.MAX_IMAGES) problems += "A project can hold at most ${ProjectImages.MAX_IMAGES} images"
    if (ProjectImages.totalBytes(images) > ProjectImages.MAX_TOTAL_BYTES) {
        problems += "A project's images can take at most ${ProjectImages.MAX_TOTAL_BYTES / (1024 * 1024)} MB"
    }
    for (scene in scenes) {
        val parents = scene.objects.associate { it.id to it.parent }
        for (obj in scene.objects) {
            val components = obj.components.map { it.componentId }
            if (components.toSet().size != components.size) problems += "${obj.name} has the same component twice"
            val parent = obj.parent ?: continue
            if (parent !in parents) {
                problems += "${obj.name} has a missing parent"
                continue
            }
            var cursor: String? = parent
            var steps = 0
            while (cursor != null && steps++ <= parents.size) {
                if (cursor == obj.id) {
                    problems += "${obj.name} is inside itself"
                    break
                }
                cursor = parents[cursor]
            }
        }
    }
    return problems.distinct()
}
