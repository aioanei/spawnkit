package dev.spawnkit.editing

import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.ComponentSeed
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.PropertyDescriptor
import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.SceneTemplate
import dev.spawnkit.runtime.TemplateObject
import dev.spawnkit.runtime.World

/**
 * The shared editing core: holds the current [project], applies validated [EditOperation]s, and keeps a bounded
 * undo/redo history. Not thread-safe; use it from one thread (the UI thread in the builder).
 */
public class Editor(
    /** Runtime whose installed descriptors define what can be authored and played. */
    public val runtime: Runtime,
    initial: Project = Project.blank(),
    private val historyLimit: Int = 200,
) {
    private val applier = OperationApplier(runtime)
    private val undoStack = ArrayDeque<Pair<Project, String>>()
    private val redoStack = ArrayDeque<Pair<Project, String>>()
    private val listeners = mutableListOf<(Project) -> Unit>()

    /** The current project. */
    public var project: Project = initial
        private set

    /** Incremented on every change, including undo and redo. */
    public var revision: Long = 0
        private set

    /** Label of the step [undo] would revert, or `null`. */
    public val undoLabel: String? get() = undoStack.lastOrNull()?.second

    /** Label of the step [redo] would reapply, or `null`. */
    public val redoLabel: String? get() = redoStack.lastOrNull()?.second

    /** Registers a change listener, called after every applied change. Returns a function that unregisters it. */
    public fun onChange(listener: (Project) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Validates and applies [operation] as one undoable step. A rejection changes nothing. */
    public fun apply(operation: EditOperation): OperationResult {
        val (next, result) = applier.apply(project, operation)
        if (result is OperationResult.Applied && next != project) {
            undoStack.addLast(project to operation.label)
            if (undoStack.size > historyLimit) undoStack.removeFirst()
            redoStack.clear()
            commit(next)
        }
        return result
    }

    /** Reverts the last step. Returns `false` when there is nothing to undo. */
    public fun undo(): Boolean {
        val (previous, label) = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(project to label)
        commit(previous)
        return true
    }

    /** Reapplies the last undone step. Returns `false` when there is nothing to redo. */
    public fun redo(): Boolean {
        val (next, label) = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(project to label)
        commit(next)
        return true
    }

    /** Replaces the whole project (for example after opening a file) and clears history. */
    public fun load(project: Project) {
        undoStack.clear()
        redoStack.clear()
        commit(project)
    }

    /** Validates a single authored value as [EditOperation.SetProperty] would, without applying anything. */
    public fun validate(
        componentId: String,
        propertyId: String,
        value: Any,
    ): String? =
        when (val result = applier.apply(project, EditOperation.Batch(emptyList(), "check")).second) {
            is OperationResult.Rejected -> result.message
            is OperationResult.Applied -> runCatching { applier.validateValue(componentId, propertyId, value) }.exceptionOrNull()?.message
        }

    /**
     * Builds a fresh world containing scene [sceneId] of the current project. The same function serves the editor
     * preview (never stepped) and Play (stepped); later edits never affect a world already built.
     */
    public fun buildWorld(sceneId: String = project.scenes.first().id): SceneWorld = buildSceneWorld(runtime, project, sceneId)

    private fun commit(next: Project) {
        project = next
        revision++
        listeners.toList().forEach { it(next) }
    }
}

/** A world built from an authored scene, with the mapping between authoring objects and runtime entities. */
public class SceneWorld internal constructor(
    /** The built world. */
    public val world: World,
    private val entities: Map<String, EntityHandle>,
) {
    /** The entity created for authoring object [objectId], or `null`. */
    public fun entity(objectId: String): EntityHandle? = entities[objectId]

    /** The authoring object an entity was created from, or `null` for entities created later (for example spawned during play). */
    public fun objectId(entity: EntityHandle): String? = if (world.isAlive(entity)) world.identity(entity)?.objectId else null
}

/** Builds [sceneId] of [project] into a fresh world of [runtime] through a scene template. */
public fun buildSceneWorld(
    runtime: Runtime,
    project: Project,
    sceneId: String,
): SceneWorld {
    val scene = requireNotNull(project.scene(sceneId)) { "Unknown scene '$sceneId'" }
    val objects =
        orderedForTemplate(scene).map { obj ->
            TemplateObject(obj.id, obj.parent, obj.components.map { seedFor(runtime, it) })
        }
    val world = runtime.createWorld()
    val instance = world.instantiate(SceneTemplate(scene.id, objects))
    return SceneWorld(world, scene.objects.associate { it.id to checkNotNull(instance.entity(it.id)) })
}

/** Template order is authoring order; sibling order within a parent follows it. */
private fun orderedForTemplate(scene: SceneDoc): List<ObjectDoc> = scene.objects

private fun seedFor(
    runtime: Runtime,
    doc: ComponentDoc,
): ComponentSeed<*> {
    val descriptor = requireNotNull(runtime.catalog.firstOrNull { it.component.id == doc.componentId }) { "Component '${doc.componentId}' is not installed" }
    return seed(descriptor, doc)
}

private fun <T : Any> seed(
    descriptor: ComponentDescriptor<T>,
    doc: ComponentDoc,
): ComponentSeed<T> =
    ComponentSeed(descriptor.component) {
        doc.values.entries.fold(descriptor.create()) { value, (propertyId, authored) ->
            val property = descriptor.property(propertyId) ?: return@fold value
            applyErased(property, value, authored)
        }
    }

@Suppress("UNCHECKED_CAST") // Authored values were coerced to the property's kind type by the operation applier.
private fun <T : Any, V : Any> applyErased(
    property: PropertyDescriptor<T, V>,
    component: T,
    authored: Any,
): T = property.with(component, property.kind.coerce(authored) ?: return component)
