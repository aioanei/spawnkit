package dev.spawnkit.app.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.gameplay.BlockLocation
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.graphics.CameraView
import dev.spawnkit.graphics.RenderList
import dev.spawnkit.graphics.renderList
import dev.spawnkit.program.PrepareResult
import dev.spawnkit.program.Program
import dev.spawnkit.program.prepareProgram
import dev.spawnkit.runtime.InputAction
import dev.spawnkit.runtime.InputFrame
import dev.spawnkit.runtime.Pointer

/** A ready-made object the creator can drop into the scene. */
data class ObjectPreset(
    val name: String,
    val description: String,
    val components: (x: Double, y: Double) -> List<ComponentDoc>,
)

/** First-party presets built only from general engine components. */
val DefaultPresets: List<ObjectPreset> =
    listOf(
        ObjectPreset("Box", "A colored square") { x, y -> placed(x, y) + shape("rectangle", 1.0, 1.0, 0xFF4CAF50) },
        ObjectPreset("Circle", "A round shape") { x, y -> placed(x, y) + shape("ellipse", 1.0, 1.0, 0xFFFFC107) },
        ObjectPreset("Platform", "A wide floor piece") { x, y -> placed(x, y) + shape("rectangle", 6.0, 0.6, 0xFF8D6E63) },
        ObjectPreset("Player", "The hero of your game") { x, y ->
            placed(x, y) + shape("rectangle", 0.9, 0.9, 0xFF2196F3) + tag("player")
        },
        ObjectPreset("Coin", "Something to collect") { x, y -> placed(x, y) + shape("ellipse", 0.6, 0.6, 0xFFFFC107) + tag("coin") },
        ObjectPreset("Score", "A number shown on screen") { x, y ->
            placed(x, y) +
                listOf(
                    ComponentDoc("graphics:text", mapOf("text" to "Score: 0")),
                    ComponentDoc("gameplay:number"),
                ) + tag("score")
        },
        ObjectPreset("Camera", "Chooses what players see") { x, y ->
            placed(x, y) + ComponentDoc("graphics:camera")
        },
    )

private fun tag(name: String) = listOf(ComponentDoc("gameplay:tag", mapOf("tag" to name)))

private fun placed(
    x: Double,
    y: Double,
) = listOf(ComponentDoc("graphics:transform", mapOf("x" to x, "y" to y)))

private fun shape(
    form: String,
    width: Double,
    height: Double,
    color: Long,
) = ComponentDoc("graphics:shape", mapOf("form" to form, "width" to width, "height" to height, "color" to color))

/**
 * A running play session: an isolated world built from the scene when Play was pressed, plus the input the player is
 * giving it. Keyboard keys, on-screen controls and pointers are combined into one [InputFrame] per tick.
 */
class PlaySession(
    val scene: SceneWorld,
    private val actions: Map<String, InputAction>,
) {
    /** Incremented after every presented frame so the viewport redraws. */
    var frame by mutableLongStateOf(0L)

    private val held = mutableMapOf<String, MutableSet<String>>()
    private val pointers = LinkedHashMap<Int, Pointer>()
    private val unsubmitted = mutableSetOf<Int>()
    private val releaseAfterSubmit = mutableSetOf<Int>()

    /** Starts holding action [actionId] from [source] (for example "keyboard" or "touch"). */
    fun press(
        actionId: String,
        source: String,
    ) {
        if (actionId in actions) held.getOrPut(actionId) { mutableSetOf() } += source
    }

    /** Stops holding action [actionId] from [source]; the action stays held while another source holds it. */
    fun release(
        actionId: String,
        source: String,
    ) {
        held[actionId]?.let {
            it -= source
            if (it.isEmpty()) held -= actionId
        }
    }

    /** A pointer went down at world position ([x], [y]). */
    fun pointerDown(
        id: Int,
        x: Double,
        y: Double,
    ) {
        pointers[id] = Pointer(id, x, y, pressed = true)
        unsubmitted += id
        releaseAfterSubmit -= id
    }

    /** A pressed pointer moved to world position ([x], [y]). */
    fun pointerMoved(
        id: Int,
        x: Double,
        y: Double,
    ) {
        if (id in pointers) pointers[id] = Pointer(id, x, y, pressed = true)
    }

    /** A pointer was lifted. A press no tick has seen yet is still delivered once, so quick taps are never lost. */
    fun pointerUp(id: Int) {
        if (id in unsubmitted) releaseAfterSubmit += id else pointers -= id
    }

    /** The input for the next tick. */
    fun nextInput(): InputFrame {
        val frame = InputFrame(down = held.keys.mapNotNull(actions::get).toSet(), pointers = pointers.values.toList())
        unsubmitted.clear()
        releaseAfterSubmit.forEach { pointers -= it }
        releaseAfterSubmit.clear()
        return frame
    }
}

/**
 * UI state of the builder, shared by every platform layout. All project changes go through [Editor] operations, so
 * the UI, AI agents and tests use one validated, undoable path.
 */
@Stable
class EditorState(
    /** The same engine exported players install, so play in the builder matches the released game. */
    val engine: StandardEngine = StandardEngine(),
    project: Project = Project.blank(),
) {
    val editor = Editor(engine.runtime, project, programs = engine.programs)

    /** Mirrors [Editor.revision] so Compose recomposes after every change. */
    var revision by mutableLongStateOf(0L)
        private set

    var sceneId by mutableStateOf(
        editor.project.scenes
            .first()
            .id,
    )
    var selection by mutableStateOf<String?>(null)
    var camera by mutableStateOf(CameraView(zoom = 1.0))
    var play by mutableStateOf<PlaySession?>(null)
        private set

    /** The last rejection, shown to the creator in plain language. */
    var message by mutableStateOf<String?>(null)
    var showAdvanced by mutableStateOf(false)

    /** Live offset of an object being dragged, applied to the preview without creating undo steps. */
    var dragging by mutableStateOf<Pair<String, Pair<Double, Double>>?>(null)

    init {
        editor.onChange {
            revision = editor.revision
            if (selection != null && editor.project.scene(sceneId)?.find(selection!!) == null) selection = null
        }
    }

    val project: Project get() = revision.let { editor.project }
    val scene get() = checkNotNull(project.scene(sceneId))

    /** Applies [operation]; on rejection keeps the project and shows the explanation. */
    fun apply(operation: EditOperation): OperationResult {
        val result = editor.apply(operation)
        message = (result as? OperationResult.Rejected)?.message
        return result
    }

    /** Adds [preset] at world point ([x], [y]) and selects it. */
    fun add(
        preset: ObjectPreset,
        x: Double = camera.centerX,
        y: Double = camera.centerY,
    ) {
        val taken = scene.objects.count { it.name == preset.name || it.name.startsWith(preset.name + " ") }
        val name = if (taken == 0) preset.name else "${preset.name} ${taken + 1}"
        val result = apply(EditOperation.AddObject(sceneId, name, components = preset.components(round(x), round(y))))
        if (result is OperationResult.Applied) selection = result.createdIds.single()
    }

    fun undo() {
        editor.undo()
        message = null
    }

    fun redo() {
        editor.redo()
        message = null
    }

    fun deleteSelection() {
        val id = selection ?: return
        apply(EditOperation.RemoveObject(sceneId, id, "Delete ${scene.find(id)?.name}"))
    }

    fun duplicateSelection() {
        val id = selection ?: return
        val result = apply(EditOperation.DuplicateObject(sceneId, id))
        if (result is OperationResult.Applied) selection = result.createdIds.first()
    }

    /** Commits a finished drag of [objectId] by a local-space offset as one undoable step. */
    fun commitMove(
        objectId: String,
        dx: Double,
        dy: Double,
    ) {
        dragging = null
        val obj = scene.find(objectId) ?: return
        val values = obj.component("graphics:transform")?.values ?: return
        val x = ((values["x"] as? Double) ?: 0.0) + dx
        val y = ((values["y"] as? Double) ?: 0.0) + dy
        apply(
            EditOperation.Batch(
                listOf(
                    EditOperation.SetProperty(sceneId, objectId, "graphics:transform", "x", round(x)),
                    EditOperation.SetProperty(sceneId, objectId, "graphics:transform", "y", round(y)),
                ),
                "Move ${obj.name}",
            ),
        )
    }

    /** Moves the selected object by a local-space offset, for keyboard nudging. Each nudge is one undo step. */
    fun nudgeSelection(
        dx: Double,
        dy: Double,
    ) {
        val id = selection ?: return
        if (canMove(id)) commitMove(id, dx, dy)
    }

    fun startPlay() {
        selection = null
        play = PlaySession(editor.buildWorld(sceneId), engine.actions)
    }

    /**
     * A plain-language problem with the running game's rules, naming the object: the first rule that stopped, or
     * objects whose rules have problems and play without them. `null` when all rules are fine.
     */
    fun playProblem(): String? {
        val session = play ?: return null
        val world = session.scene.world
        for (entity in world.entities(engine.programs.behavior)) {
            val fault = world.get(entity, engine.programs.behavior)?.fault ?: continue
            val name = session.scene.objectId(entity)?.let { scene.find(it)?.name } ?: "An object"
            return "$name's rules stopped: ${fault.message}"
        }
        val broken =
            session.scene.diagnostics.keys
                .mapNotNull { scene.find(it)?.name }
        if (broken.isEmpty()) return null
        return "${broken.joinToString()} ${if (broken.size == 1) "has" else "have"} rule problems and play without rules: " +
            session.scene.diagnostics.values
                .first()
                .first()
                .message
    }

    fun stopPlay() {
        play = null
    }

    /** The world shown in the viewport: the play world while playing, otherwise a preview of the scene. */
    fun previewWorld(): SceneWorld {
        val built = editor.buildWorld(sceneId)
        dragging?.let { (id, offset) ->
            val entity = built.entity(id) ?: return@let
            val transform = built.world.get(entity, engine.graphics.transform) ?: return@let
            built.world.set(
                entity,
                engine.graphics.transform,
                transform.copy(
                    x = transform.x + offset.first,
                    y =
                        transform.y + offset.second,
                ),
            )
        }
        return built
    }

    fun frame(world: SceneWorld): RenderList = engine.graphics.renderList(world.world)

    /** The authored object ID's local-space translation for a world-space drag delta, honouring parent rotation and scale. */
    fun localDelta(
        world: SceneWorld,
        objectId: String,
        dx: Double,
        dy: Double,
    ): Pair<Double, Double> {
        val entity = world.entity(objectId) ?: return dx to dy
        val parent = world.world.parent(entity) ?: return dx to dy
        val inverse = frame(world).worldTransform(parent).inverse() ?: return dx to dy
        return (inverse.a * dx + inverse.c * dy) to (inverse.b * dx + inverse.d * dy)
    }

    /** Whether the object can be dragged (it has a placement). */
    fun canMove(objectId: String): Boolean = scene.find(objectId)?.component("graphics:transform") != null

    /** [objectId]'s rules as the rule editor shows them: blocks, or advanced rules kept unchanged. */
    fun ruleItems(objectId: String): List<RuleItem> = RuleBlocks.decompile(scene.find(objectId)?.rules.orEmpty())

    /** Replaces [objectId]'s rules as one undoable step. */
    fun setRuleItems(
        objectId: String,
        items: List<RuleItem>,
    ) {
        val name = scene.find(objectId)?.name ?: return
        apply(EditOperation.SetRules(sceneId, objectId, RuleBlocks.compileItems(items), "Change rules of $name"))
    }

    /**
     * Problems in [objectId]'s rules, checked exactly as Play will check them. Each is keyed by the block it belongs to,
     * or `null` for problems outside blocks.
     */
    fun ruleProblems(objectId: String): List<Pair<BlockLocation?, String>> {
        val rules = scene.find(objectId)?.rules.orEmpty()
        if (rules.isEmpty()) return emptyList()
        val result = prepareProgram(Program("check.$objectId", handlers = rules), engine.host)
        return (result as? PrepareResult.Invalid)?.diagnostics?.map { RuleBlocks.locate(it.source.nodeId) to it.message }.orEmpty()
    }

    /** Tags used in the current scene, for pickers. */
    fun tags(): List<String> =
        scene.objects
            .mapNotNull { (it.component("gameplay:tag")?.values?.get("tag") as? String)?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/** Rounds to 0.01 world units so dragged positions stay tidy in the inspector. */
private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
