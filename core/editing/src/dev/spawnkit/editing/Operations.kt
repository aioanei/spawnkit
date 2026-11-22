package dev.spawnkit.editing

import dev.spawnkit.program.Handler
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.ValueKind

/**
 * A typed editor operation. The visual editor, Koog agents and tests all change projects only through operations,
 * so every change is validated, undoable and explainable.
 */
public sealed interface EditOperation {
    /** Short creator-facing description used for undo history, for example "Move Hero". */
    public val label: String

    /** Renames the project as one undoable step. */
    public data class RenameProject(
        /** Creator-facing game name. */
        public val name: String,
        override val label: String = "Rename project",
    ) : EditOperation

    /** Adds an object with [components] (defaults unless values are given) under [parent], appended or at [index]. */
    public data class AddObject(
        public val sceneId: String,
        public val name: String,
        public val parent: String? = null,
        public val components: List<ComponentDoc> = emptyList(),
        public val index: Int = -1,
        override val label: String = "Add $name",
    ) : EditOperation

    /** Removes an object and all its descendants. */
    public data class RemoveObject(
        public val sceneId: String,
        public val objectId: String,
        override val label: String = "Delete object",
    ) : EditOperation

    /** Renames an object. */
    public data class RenameObject(
        public val sceneId: String,
        public val objectId: String,
        public val name: String,
        override val label: String = "Rename to $name",
    ) : EditOperation

    /** Moves an object under [parent] (`null` = root); cycles are rejected. */
    public data class ReparentObject(
        public val sceneId: String,
        public val objectId: String,
        public val parent: String?,
        override val label: String = "Move in hierarchy",
    ) : EditOperation

    /** Copies an object and its descendants with fresh IDs, placed right after the original. */
    public data class DuplicateObject(
        public val sceneId: String,
        public val objectId: String,
        override val label: String = "Duplicate",
    ) : EditOperation

    /** Adds a described component with default values. */
    public data class AddComponent(
        public val sceneId: String,
        public val objectId: String,
        public val componentId: String,
        override val label: String = "Add component",
    ) : EditOperation

    /** Removes a component. */
    public data class RemoveComponent(
        public val sceneId: String,
        public val objectId: String,
        public val componentId: String,
        override val label: String = "Remove component",
    ) : EditOperation

    /** Sets one property value, validated by the property's value kind. */
    public data class SetProperty(
        public val sceneId: String,
        public val objectId: String,
        public val componentId: String,
        public val propertyId: String,
        public val value: Any,
        override val label: String = "Change $propertyId",
    ) : EditOperation

    /** Adds an empty scene. */
    public data class AddScene(
        public val name: String,
        override val label: String = "Add scene $name",
    ) : EditOperation

    /** Renames a scene. */
    public data class RenameScene(
        public val sceneId: String,
        public val name: String,
        override val label: String = "Rename scene",
    ) : EditOperation

    /** Removes a scene with its objects. A game always keeps at least one scene. */
    public data class RemoveScene(
        public val sceneId: String,
        override val label: String = "Delete scene",
    ) : EditOperation

    /** Moves a scene to position [toIndex] (0-based). The first scene is where the game starts. */
    public data class MoveScene(
        public val sceneId: String,
        public val toIndex: Int,
        override val label: String = "Move scene",
    ) : EditOperation

    /**
     * Replaces an object's rules as one undoable step. Rules are not type-checked here, so creators can save rules that
     * are still being built; worlds report their problems ([SceneWorld.diagnostics]).
     */
    public data class SetRules(
        public val sceneId: String,
        public val objectId: String,
        public val rules: List<Handler>,
        override val label: String = "Change rules",
    ) : EditOperation

    /** Applies [operations] in order as one undoable step; any rejection rejects the whole batch. */
    public data class Batch(
        public val operations: List<EditOperation>,
        override val label: String,
    ) : EditOperation
}

/** Outcome of applying an operation. */
public sealed interface OperationResult {
    /** Applied; [createdIds] lists object or scene IDs created, in creation order. */
    public data class Applied(
        public val createdIds: List<String> = emptyList(),
    ) : OperationResult

    /** Rejected without changes, with a plain-language explanation. */
    public data class Rejected(
        public val message: String,
    ) : OperationResult
}

private class Rejection(
    message: String,
) : Exception(message)

/** Pure operation semantics: validates [operation] against installed descriptors and returns the new project. */
internal class OperationApplier(
    private val runtime: Runtime,
) {
    fun apply(
        project: Project,
        operation: EditOperation,
        idFloor: Long = 1,
    ): Pair<Project, OperationResult> =
        try {
            val created = mutableListOf<String>()
            // IDs are minted above every ID ever used, so undo or a stale counter can never cause reuse.
            val minting = project.copy(nextId = maxOf(project.nextId, idFloor, project.maxIdSuffix() + 1))
            val result = applyInternal(minting, operation, created)
            result to OperationResult.Applied(created.toList())
        } catch (rejection: Rejection) {
            project to OperationResult.Rejected(rejection.message ?: "This change is not possible")
        }

    private fun applyInternal(
        project: Project,
        operation: EditOperation,
        created: MutableList<String>,
    ): Project =
        when (operation) {
            is EditOperation.RenameProject -> {
                requireName(operation.name)
                project.copy(name = operation.name.trim())
            }

            is EditOperation.Batch -> {
                operation.operations.fold(project) { current, next -> applyInternal(current, next, created) }
            }

            is EditOperation.AddScene -> {
                requireName(operation.name)
                val id = "scene-${project.nextId}"
                created += id
                project.copy(scenes = project.scenes + SceneDoc(id, operation.name.trim()), nextId = project.nextId + 1)
            }

            is EditOperation.RenameScene -> {
                requireName(operation.name)
                editScene(project, operation.sceneId) { it.copy(name = operation.name.trim()) }
            }

            is EditOperation.RemoveScene -> {
                if (project.scene(operation.sceneId) == null) reject("That scene no longer exists")
                if (project.scenes.size == 1) reject("A game needs at least one scene")
                project.copy(scenes = project.scenes.filter { it.id != operation.sceneId })
            }

            is EditOperation.MoveScene -> {
                val scene = project.scene(operation.sceneId) ?: reject("That scene no longer exists")
                if (operation.toIndex !in project.scenes.indices) reject("Choose a position from 1 to ${project.scenes.size}")
                val rest = project.scenes.filter { it.id != scene.id }
                project.copy(scenes = rest.toMutableList().apply { add(operation.toIndex, scene) })
            }

            is EditOperation.AddObject -> {
                addObject(project, operation, created)
            }

            is EditOperation.RemoveObject -> {
                editScene(project, operation.sceneId) { scene ->
                    requireObject(scene, operation.objectId)
                    val removed = scene.subtree(operation.objectId).toSet()
                    scene.copy(objects = scene.objects.filter { it.id !in removed })
                }
            }

            is EditOperation.RenameObject -> {
                requireName(operation.name)
                editObject(project, operation.sceneId, operation.objectId) { it.copy(name = operation.name.trim()) }
            }

            is EditOperation.ReparentObject -> {
                editScene(project, operation.sceneId) { scene ->
                    requireObject(scene, operation.objectId)
                    operation.parent?.let { parent ->
                        requireObject(scene, parent)
                        if (parent in scene.subtree(operation.objectId)) reject("An object cannot be placed inside itself")
                    }
                    // Move to the end of the authoring order so it becomes the last child of its new parent.
                    val moved = checkNotNull(scene.find(operation.objectId)).copy(parent = operation.parent)
                    scene.copy(objects = scene.objects.filter { it.id != operation.objectId } + moved)
                }
            }

            is EditOperation.DuplicateObject -> {
                duplicate(project, operation, created)
            }

            is EditOperation.AddComponent -> {
                val descriptor = descriptor(operation.componentId)
                editObject(project, operation.sceneId, operation.objectId) { obj ->
                    if (obj.component(operation.componentId) !=
                        null
                    ) {
                        reject("${obj.name} already has ${descriptor.component.authoring.name}")
                    }
                    obj.copy(components = obj.components + ComponentDoc(operation.componentId))
                }
            }

            is EditOperation.RemoveComponent -> {
                editObject(project, operation.sceneId, operation.objectId) { obj ->
                    if (obj.component(operation.componentId) == null) reject("${obj.name} has no such component")
                    obj.copy(components = obj.components.filter { it.componentId != operation.componentId })
                }
            }

            is EditOperation.SetRules -> {
                if (operation.rules.size > MAX_RULES) reject("An object can have at most $MAX_RULES rules")
                editObject(project, operation.sceneId, operation.objectId) { it.copy(rules = operation.rules.toList()) }
            }

            is EditOperation.SetProperty -> {
                val normalized = validateValue(operation.componentId, operation.propertyId, operation.value)
                editObject(project, operation.sceneId, operation.objectId) { obj ->
                    val doc =
                        obj.component(operation.componentId)
                            ?: reject("${obj.name} has no ${descriptor(operation.componentId).component.authoring.name}")
                    val updated = doc.copy(values = doc.values + (operation.propertyId to normalized))
                    obj.copy(components = obj.components.map { if (it.componentId == operation.componentId) updated else it })
                }
            }
        }

    private fun addObject(
        project: Project,
        operation: EditOperation.AddObject,
        created: MutableList<String>,
    ): Project {
        requireName(operation.name)
        val ids = operation.components.map { it.componentId }
        if (ids.toSet().size != ids.size) reject("An object cannot have the same component twice")
        val components =
            operation.components.map { doc ->
                descriptor(doc.componentId)
                doc.copy(values = doc.values.mapValues { (property, value) -> validateValue(doc.componentId, property, value) })
            }
        val id = "obj-${project.nextId}"
        created += id
        val next =
            editScene(project, operation.sceneId) { scene ->
                operation.parent?.let { requireObject(scene, it) }
                val obj = ObjectDoc(id, operation.name.trim(), operation.parent, components)
                if (operation.index < -1) reject("Position ${operation.index} is invalid")
                if (operation.index == -1) {
                    scene.copy(objects = scene.objects + obj)
                } else {
                    // Insert before the index-th existing sibling, keeping authoring order meaningful.
                    val siblings = scene.childrenOf(operation.parent)
                    if (operation.index > siblings.size) reject("Position ${operation.index} is outside 0..${siblings.size}")
                    val anchor = siblings.getOrNull(operation.index)
                    val at = if (anchor == null) scene.objects.size else scene.objects.indexOf(anchor)
                    scene.copy(objects = scene.objects.toMutableList().apply { add(at, obj) })
                }
            }
        return next.copy(nextId = next.nextId + 1)
    }

    private fun duplicate(
        project: Project,
        operation: EditOperation.DuplicateObject,
        created: MutableList<String>,
    ): Project {
        val scene = project.scene(operation.sceneId) ?: reject("That scene no longer exists")
        requireObject(scene, operation.objectId)
        val originals = scene.subtree(operation.objectId).map { checkNotNull(scene.find(it)) }
        var counter = project.nextId
        val mapping = originals.associate { it.id to "obj-${counter++}" }
        val copies =
            originals.map { original ->
                original.copy(
                    id = mapping.getValue(original.id),
                    name = if (original.id == operation.objectId) "${original.name} copy".take(MAX_NAME) else original.name,
                    parent = if (original.id == operation.objectId) original.parent else mapping.getValue(checkNotNull(original.parent)),
                )
            }
        created += copies.map { it.id }
        val insertAt = scene.objects.indexOfFirst { it.id == operation.objectId } + 1
        val objects = scene.objects.toMutableList().apply { addAll(insertAt, copies) }
        return project.copy(scenes = project.scenes.map { if (it.id == scene.id) scene.copy(objects = objects) else it }, nextId = counter)
    }

    private fun editScene(
        project: Project,
        sceneId: String,
        change: (SceneDoc) -> SceneDoc,
    ): Project {
        val scene = project.scene(sceneId) ?: reject("That scene no longer exists")
        val updated = change(scene)
        return project.copy(scenes = project.scenes.map { if (it.id == sceneId) updated else it })
    }

    private fun editObject(
        project: Project,
        sceneId: String,
        objectId: String,
        change: (ObjectDoc) -> ObjectDoc,
    ): Project =
        editScene(project, sceneId) { scene ->
            val obj = scene.find(objectId) ?: reject("That object no longer exists")
            val updated = change(obj)
            scene.copy(objects = scene.objects.map { if (it.id == objectId) updated else it })
        }

    private fun requireObject(
        scene: SceneDoc,
        id: String,
    ) {
        if (scene.find(id) == null) reject("That object no longer exists")
    }

    private fun requireName(name: String) {
        if (name.isBlank()) reject("Give it a name")
        if (name.trim().length > MAX_NAME) reject("Use a name of at most $MAX_NAME characters")
    }

    fun descriptor(componentId: String): ComponentDescriptor<*> =
        runtime.catalog.firstOrNull { it.component.id == componentId } ?: reject("No installed component is called '$componentId'")

    /** Validates and normalizes an authored value without a world (object references are not authorable yet). */
    fun validateValue(
        componentId: String,
        propertyId: String,
        value: Any,
    ): Any {
        val descriptor = descriptor(componentId)
        val property = descriptor.property(propertyId) ?: reject("${descriptor.component.authoring.name} has no '$propertyId'")
        return checkKind(property.kind, property.authoring.name, value)
    }

    private fun <V : Any> checkKind(
        kind: ValueKind<V>,
        name: String,
        value: Any,
    ): V {
        if (kind is ValueKind.ObjectReference) reject("$name cannot be chosen in the editor yet")
        val coerced = kind.coerce(value) ?: reject("$name needs a different kind of value")
        kind.problem(coerced, null)?.let { reject("$name: $it") }
        return coerced
    }
}

private fun reject(message: String): Nothing = throw Rejection(message)

private const val MAX_NAME = 80

private const val MAX_RULES = 200

/** Largest numeric suffix among `scene-N` and `obj-N` IDs in the project, or 0. */
internal fun Project.maxIdSuffix(): Long =
    (scenes.map { it.id } + scenes.flatMap { scene -> scene.objects.map { it.id } })
        .mapNotNull { id -> id.substringAfterLast('-').toLongOrNull() }
        .maxOrNull() ?: 0
