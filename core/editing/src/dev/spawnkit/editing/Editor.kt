package dev.spawnkit.editing

import dev.spawnkit.program.Diagnostic
import dev.spawnkit.program.PrepareResult
import dev.spawnkit.program.Program
import dev.spawnkit.program.ProgramFeature
import dev.spawnkit.program.prepareProgram
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.ComponentSeed
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.PropertyDescriptor
import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.SceneInstance
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
    /** Executes object rules in built worlds; without it, rules are kept in the project but not run. */
    public val programs: ProgramFeature? = null,
) {
    private val applier = OperationApplier(runtime)
    private val undoStack = ArrayDeque<Pair<Project, String>>()
    private val redoStack = ArrayDeque<Pair<Project, String>>()
    private val listeners = mutableListOf<(Project) -> Unit>()
    private var idFloor = 1L

    /** The current project. */
    public var project: Project = initial.also(::requireUsable)
        private set

    /** Incremented on every change, including undo and redo. */
    public var revision: Long = 0
        private set

    /** Label of the step [undo] would revert, or `null`. */
    public val undoLabel: String? get() = undoStack.lastOrNull()?.second

    /** Label of the step [redo] would reapply, or `null`. */
    public val redoLabel: String? get() = redoStack.lastOrNull()?.second

    /**
     * Immutable persistence snapshot. Its counter includes IDs retired by undo, so save/restart cannot hand out an
     * identifier that this editing session already used. Undo history itself is deliberately not serialized.
     */
    public fun saveSnapshot(): Project = project.copy(nextId = maxOf(project.nextId, idFloor, project.maxIdSuffix() + 1))

    /** Registers a change listener, called after every applied change. Returns a function that unregisters it. */
    public fun onChange(listener: (Project) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Validates and applies [operation] as one undoable step. A rejection changes nothing. */
    public fun apply(operation: EditOperation): OperationResult {
        val (next, result) = applier.apply(project, operation, idFloor)
        if (result is OperationResult.Applied && next.copy(nextId = project.nextId) != project) {
            idFloor = maxOf(idFloor, next.nextId)
            undoStack.addLast(project to operation.label)
            if (undoStack.size > historyLimit) undoStack.removeFirst()
            redoStack.clear()
            commit(next)
        }
        return result
    }

    /** Applies a built catalog operation as one undoable step; a [BuildResult.Rejected] changes nothing. */
    public fun apply(result: BuildResult): OperationResult =
        when (result) {
            is BuildResult.Ready -> apply(result.operation)
            is BuildResult.Rejected -> OperationResult.Rejected(result.message)
        }

    /** The current project and installed capabilities, for building catalog operations ([OperationCatalog.build]). */
    public fun catalogContext(): CatalogContext = CatalogContext(project, runtime)

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
        requireUsable(project)
        undoStack.clear()
        redoStack.clear()
        commit(project)
    }

    /**
     * Builds an unstepped preview using available components and valid values. Unavailable data remains in [project];
     * [capabilityProblems] explains omissions. A preview never executes rules and is not a substitute for [buildWorld].
     */
    public fun buildPreview(sceneId: String): SceneWorld = buildSceneWorld(runtime, project.previewProjection(runtime), sceneId)

    /** Validates a single authored value as [EditOperation.SetProperty] would, without applying anything. */
    public fun validate(
        componentId: String,
        propertyId: String,
        value: Any,
    ): String? = runCatching { applier.validateValue(componentId, propertyId, value) }.exceptionOrNull()?.message

    /**
     * Builds a fresh world containing scene [sceneId] of the current project. The same function serves the editor
     * preview (never stepped) and Play (stepped); later edits never affect a world already built.
     */
    public fun buildWorld(sceneId: String = project.scenes.first().id): SceneWorld = buildSceneWorld(runtime, project, sceneId, programs)

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
    /** Problems in objects' rules, by object ID. Those objects play without their rules. */
    public val diagnostics: Map<String, List<Diagnostic>> = emptyMap(),
) {
    /** The entity created for authoring object [objectId], or `null`. */
    public fun entity(objectId: String): EntityHandle? = entities[objectId]

    /** The authoring object an entity was created from, or `null` for entities created later (for example spawned during play). */
    public fun objectId(entity: EntityHandle): String? = if (world.isAlive(entity)) world.identity(entity)?.objectId else null
}

/**
 * Builds [sceneId] of [project] into a fresh world of [runtime] through a scene template. With [programs], each
 * object's rules are prepared and attached; rules with problems are reported in [SceneWorld.diagnostics] and skipped.
 */
public fun buildSceneWorld(
    runtime: Runtime,
    project: Project,
    sceneId: String,
    programs: ProgramFeature? = null,
): SceneWorld {
    val problems = project.capabilityProblems(runtime)
    require(problems.isEmpty()) { problems.first() }
    val scene = requireNotNull(project.scene(sceneId)) { "Unknown scene '$sceneId'" }
    val objects =
        orderedForTemplate(scene).map { obj ->
            TemplateObject(obj.id, obj.parent, obj.components.map { seedFor(runtime, it) })
        }
    val world = runtime.createWorld()
    val instance = world.instantiate(SceneTemplate(scene.id, objects))
    val entities = scene.objects.associate { it.id to checkNotNull(instance.entity(it.id)) }
    val diagnostics = LinkedHashMap<String, List<Diagnostic>>()
    if (programs != null) {
        for (obj in scene.objects) {
            if (obj.rules.isEmpty()) continue
            when (val prepared = prepareRules(programs, obj)) {
                is PrepareResult.Ready -> world.set(entities.getValue(obj.id), programs.behavior, programs.instantiate(prepared.program))
                is PrepareResult.Invalid -> diagnostics[obj.id] = prepared.diagnostics
            }
        }
    }
    return SceneWorld(world, entities, diagnostics)
}

/**
 * Builds object [objectId] of scene [sceneId], with everything inside it, into the running [world] as a new copy, with
 * its rules attached through [programs] (rules with problems are skipped, as in [buildSceneWorld]). Returns the new
 * instance, or `null` when the object does not exist. Call it between ticks; spawning during play uses it.
 */
public fun instantiateObject(
    runtime: Runtime,
    world: World,
    project: Project,
    sceneId: String,
    objectId: String,
    programs: ProgramFeature? = null,
): SceneInstance? {
    val scene = project.scene(sceneId) ?: return null
    if (scene.find(objectId) == null) return null
    val subtree = scene.subtree(objectId).toSet()
    val docs = scene.objects.filter { it.id in subtree }
    val objects = docs.map { obj -> TemplateObject(obj.id, obj.parent?.takeIf { obj.id != objectId }, obj.components.map { seedFor(runtime, it) }) }
    val instance = world.instantiate(SceneTemplate("copy.$objectId", objects))
    if (programs != null) {
        for (obj in docs) {
            if (obj.rules.isEmpty()) continue
            val prepared = prepareRules(programs, obj) as? PrepareResult.Ready ?: continue
            world.set(checkNotNull(instance.entity(obj.id)), programs.behavior, programs.instantiate(prepared.program))
        }
    }
    return instance
}

/**
 * Prepares [obj]'s rules as a program. The engine's program library keeps every version it has seen, so the ID
 * includes a content fingerprint: editing rules and rebuilding never conflicts, and each checkpoint restores the
 * version it was built with.
 */
private fun prepareRules(
    programs: ProgramFeature,
    obj: ObjectDoc,
): PrepareResult {
    val base = Program("rules.${obj.id}", handlers = obj.rules)
    val first = prepareProgram(base, programs.host)
    if (first !is PrepareResult.Ready) return first
    return prepareProgram(base.copy(id = "${base.id}.${first.program.fingerprint.toULong().toString(16)}"), programs.host)
}

/** Template order is authoring order; sibling order within a parent follows it. */
private fun orderedForTemplate(scene: SceneDoc): List<ObjectDoc> = scene.objects

private fun seedFor(
    runtime: Runtime,
    doc: ComponentDoc,
): ComponentSeed<*> {
    val descriptor =
        requireNotNull(
            runtime.catalog.firstOrNull { it.component.id == doc.componentId },
        ) { "Component '${doc.componentId}' is not installed" }
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

private fun requireUsable(project: Project) {
    val problems = project.problems()
    require(problems.isEmpty()) { "This project cannot be opened: ${problems.joinToString("; ")}" }
}
