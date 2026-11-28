package dev.spawnkit.app.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.editing.BuildResult
import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectImages
import dev.spawnkit.editing.SceneWorld
import dev.spawnkit.editing.capabilityProblems
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.BlockLocation
import dev.spawnkit.gameplay.BlockPart
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.graphics.CameraView
import dev.spawnkit.graphics.RenderList
import dev.spawnkit.graphics.renderList
import dev.spawnkit.player.PlaySession
import dev.spawnkit.player.playSession
import dev.spawnkit.program.Handler
import dev.spawnkit.program.PrepareResult
import dev.spawnkit.program.Program
import dev.spawnkit.program.prepareProgram

/**
 * A ready-made object the creator can drop into the scene. It may bring [children] (name and components, placed
 * relative to it) and [rules] for the scene it is added to; everything is added as one undo step.
 */
data class ObjectPreset(
    val name: String,
    val description: String,
    val children: List<Pair<String, List<ComponentDoc>>> = emptyList(),
    val rules: (project: Project, sceneId: String) -> List<Rule> = { _, _ -> emptyList() },
    val components: (x: Double, y: Double) -> List<ComponentDoc>,
)

/** Layouts "+ Title screen" offers (C15). */
enum class TitleLayout(
    val label: String,
    val description: String,
) {
    PlayOnly("Play", "A Play button that starts the game."),
    WithLevels("Play + Levels", "Also a Levels scene with a button for every scene of the game."),
    Full("Play + Levels + Shop + How it works", "Also a Shop scene and a How it works scene, each with a Back button."),
}

/** First-party presets built only from general engine components. */
val DefaultPresets: List<ObjectPreset> =
    listOf(
        ObjectPreset("Box", "A colored square") { x, y -> placed(x, y) + shape("rectangle", 1.0, 1.0, 0xFF4CAF50) },
        ObjectPreset("Circle", "A round shape") { x, y -> placed(x, y) + shape("ellipse", 1.0, 1.0, 0xFFFFC107) },
        ObjectPreset("Platform", "A floor piece bodies stand on") { x, y ->
            placed(x, y) + shape("rectangle", 6.0, 0.6, 0xFF8D6E63) + ComponentDoc("gameplay:solid")
        },
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
        ObjectPreset(
            "Button",
            "Tap it to go to another scene",
            children = listOf("Button label" to placed(0.0, 0.0) + text("Play", 0.7)),
            rules = { project, sceneId -> listOfNotNull(nextScene(project, sceneId)?.let { goTo(it) }) },
        ) { x, y -> placed(x, y) + shape("rectangle", 4.0, 1.2, 0xFF43A047, 1) },
        ObjectPreset("Text", "Words on the screen") { x, y -> placed(x, y) + text("Text", 1.0) },
    )

/** The scene a new Button goes to: the next one, else the first other scene, else none. */
private fun nextScene(
    project: Project,
    sceneId: String,
): String? {
    val scenes = project.scenes
    val index = scenes.indexOfFirst { it.id == sceneId }
    return scenes.getOrNull(index + 1)?.id ?: scenes.firstOrNull { it.id != sceneId }?.id
}

private fun goTo(sceneId: String) = Rule(Condition.Tapped, actions = listOf(Action.GoToScene(sceneId)))

/** Builds one [EditOperation.Batch], predicting the IDs the editor will give new scenes and objects. */
private class BatchBuilder(
    private var next: Long,
) {
    val operations = mutableListOf<EditOperation>()

    fun scene(name: String): String = "scene-${next++}".also { operations += EditOperation.AddScene(name) }

    fun obj(
        sceneId: String,
        name: String,
        components: List<ComponentDoc>,
        parent: String? = null,
    ): String = "obj-${next++}".also { operations += EditOperation.AddObject(sceneId, name, parent, components) }

    fun rules(
        sceneId: String,
        objectId: String,
        rules: List<Rule>,
    ) {
        operations += EditOperation.SetRules(sceneId, objectId, RuleBlocks.compile(rules))
    }

    /** A green button with a [label] inside it that goes to scene [target] when tapped. */
    fun button(
        sceneId: String,
        name: String,
        x: Double,
        y: Double,
        label: String,
        target: String,
        width: Double = 4.4,
        size: Double = 0.6,
    ): String {
        val id = obj(sceneId, "$name button", placed(x, y) + shape("rectangle", width, 1.1, 0xFF43A047, 1))
        obj(sceneId, "$name label", placed(0.0, 0.0) + text(label, size), parent = id)
        rules(sceneId, id, listOf(goTo(target)))
        return id
    }

    /** A background and a heading, for a new scene. */
    fun backdrop(
        sceneId: String,
        heading: String,
        color: Long,
    ) {
        obj(sceneId, "Background", placed(0.0, 0.0) + shape("rectangle", 16.0, 9.0, color, -1))
        obj(sceneId, "Heading", placed(0.0, -3.2) + text(heading, 1.0))
    }
}

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
    layer: Long = 0,
) = ComponentDoc("graphics:shape", mapOf("form" to form, "width" to width, "height" to height, "color" to color, "layer" to layer))

private fun text(
    value: String,
    size: Double,
) = listOf(ComponentDoc("graphics:text", mapOf("text" to value, "size" to size, "layer" to 2L)))

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
            // The scene being edited can disappear (deleted, or its creation undone): edit the first scene instead.
            if (editor.project.scene(sceneId) == null) {
                sceneId =
                    editor.project.scenes
                        .first()
                        .id
            }
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

    /**
     * Applies an operation built by the engine's operation catalog or by typed catalog helpers such as rule operations,
     * the same path agents use. A rejection keeps the project and shows the explanation.
     */
    fun apply(result: BuildResult): OperationResult {
        val applied = editor.apply(result)
        message = (applied as? OperationResult.Rejected)?.message
        return applied
    }

    /** Applies catalog operation [id] with [arguments], the same entry agents use (ADR-0009). */
    fun applyCatalog(
        id: String,
        arguments: Map<String, Any?>,
    ): OperationResult = apply(engine.operations.build(id, arguments, editor.catalogContext()))

    /** Adds [preset] at world point ([x], [y]) and selects it. */
    fun add(
        preset: ObjectPreset,
        x: Double = camera.centerX,
        y: Double = camera.centerY,
    ) {
        val taken = scene.objects.count { it.name == preset.name || it.name.startsWith(preset.name + " ") }
        val name = if (taken == 0) preset.name else "${preset.name} ${taken + 1}"
        val components = preset.components(round(x), round(y))
        val rules = preset.rules(project, sceneId)
        if (preset.children.isEmpty() && rules.isEmpty()) {
            val result = apply(EditOperation.AddObject(sceneId, name, components = components))
            if (result is OperationResult.Applied) selection = result.createdIds.single()
            return
        }
        val batch = BatchBuilder(editor.saveSnapshot().nextId)
        val id = batch.obj(sceneId, name, components)
        for ((child, parts) in preset.children) batch.obj(sceneId, child, parts, parent = id)
        if (rules.isNotEmpty()) batch.rules(sceneId, id, rules)
        if (apply(EditOperation.Batch(batch.operations, "Add $name")) is OperationResult.Applied) selection = id
    }

    /**
     * Imports a PNG picture as a project image (the `add_image` operation, ADR-0012) and returns its ID, or `null` with
     * the reason in [message]. With [place], an object showing it is added at the centre of the view and selected, in
     * the same undo step: the Image palette piece.
     */
    fun importImage(
        name: String,
        png: ByteArray,
        place: Boolean = false,
    ): String? {
        val add = EditOperation.AddImage(name, ProjectImages.encode(png), "Import image ${name.trim()}")
        if (!place) return (apply(add) as? OperationResult.Applied)?.createdIds?.single()
        val info =
            try {
                ProjectImages.check(png)
            } catch (problem: IllegalArgumentException) {
                message = problem.message
                return null
            }
        val next = editor.saveSnapshot().nextId
        val imageId = "img-$next"
        val placed = EditOperation.AddObject(sceneId, name.trim(), components = imageComponents(imageId, info.width, info.height))
        if (apply(EditOperation.Batch(listOf(add, placed), "Add image ${name.trim()}")) !is OperationResult.Applied) return null
        selection = "obj-${next + 1}"
        return imageId
    }

    /**
     * Character Forge results (ADR-0013) join the game as one undo step: every image with its provenance, and a
     * Character showing the image of style [chosen], with a body, the player tag, and walking and jumping rules.
     */
    fun addCharacterImages(
        images: List<dev.spawnkit.ai.ForgedImage>,
        chosen: String,
    ) {
        val planned =
            dev.spawnkit.ai.CharacterRecipe.batch(
                images,
                chosen,
                sceneId,
                round(camera.centerX),
                round(camera.centerY),
                editor.saveSnapshot().nextId,
            ) ?: return
        if (apply(planned.operation) is OperationResult.Applied) selection = planned.characterId
    }

    /** Adds an object that shows image [imageId] at the centre of the view and selects it. */
    fun addImageObject(imageId: String) {
        val image = project.image(imageId) ?: return
        val info = ProjectImages.check(image.data)
        val result = apply(EditOperation.AddObject(sceneId, image.name, components = imageComponents(image.id, info.width, info.height)))
        if (result is OperationResult.Applied) selection = result.createdIds.single()
    }

    /** A sprite of [imageId] at the centre of the view whose longest side is 3 units, keeping the picture's shape. */
    private fun imageComponents(
        imageId: String,
        width: Int,
        height: Int,
    ): List<ComponentDoc> {
        val longest = maxOf(width, height).toDouble()
        val size = mapOf("asset" to imageId, "width" to 3.0 * width / longest, "height" to 3.0 * height / longest)
        return placed(round(camera.centerX), round(camera.centerY)) + ComponentDoc("graphics:sprite", size)
    }

    /**
     * Moves object [objectId] into [parent] (`null` for the top level), keeping it where it is on screen: the
     * `move_into` catalog operation, so the agent tool does exactly the same (C15).
     */
    fun moveInto(
        objectId: String,
        parent: String?,
    ): OperationResult {
        val arguments = listOfNotNull("scene" to sceneId, "object" to objectId, parent?.let { "parent" to it }).toMap()
        return apply(engine.operations.build("move_into", arguments, editor.catalogContext()))
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
        project.capabilityProblems(engine.runtime).firstOrNull()?.let {
            message = it
            return
        }
        selection = null
        play = engine.playSession(editor.project, sceneId)
    }

    /**
     * A plain-language problem with the running game's rules, naming the object: the first rule that stopped, or
     * objects whose rules have problems and play without them. `null` when all rules are fine.
     */
    fun playProblem(): String? {
        val session = play ?: return null
        session.message?.let { return it }
        // Play may have moved to another scene with "Go to scene"; name objects from the scene being played.
        val playing = session.sceneId?.let(project::scene) ?: scene
        val world = session.scene.world
        for (entity in world.entities(engine.programs.behavior)) {
            val fault = world.get(entity, engine.programs.behavior)?.fault ?: continue
            val name = session.scene.objectId(entity)?.let { playing.find(it)?.name } ?: "An object"
            return "$name's rules stopped: ${fault.message}"
        }
        val broken =
            session.scene.diagnostics.keys
                .mapNotNull { playing.find(it)?.name }
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

    /**
     * The assistant's staged game while it works, or while its proposal waits for review (C13b). The viewport draws it
     * instead of the game so creators watch the game take shape; nothing in [project] changes until the step is applied.
     */
    var assistantPreview by mutableStateOf<Project?>(null)

    /**
     * The world shown in the viewport: a preview of the scene, from [assistantPreview] when it has this scene. Play
     * worlds are drawn by the play session instead.
     */
    fun previewWorld(): SceneWorld {
        assistantPreview
            ?.takeIf { it.scene(sceneId) != null }
            ?.let { staged -> runCatching { Editor(engine.runtime, staged).buildPreview(sceneId) }.getOrNull() }
            ?.let { return it }
        val built = editor.buildPreview(sceneId)
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
        val checked = (result as? PrepareResult.Invalid)?.diagnostics?.map { RuleBlocks.locate(it.source.nodeId) to it.message }.orEmpty()
        return checked + missingScenes(rules)
    }

    /** "Go to scene" blocks whose scene was removed; playing ignores them with a message. */
    private fun missingScenes(rules: List<Handler>): List<Pair<BlockLocation?, String>> =
        RuleBlocks.decompile(rules).flatMapIndexed { index, item ->
            val rule = (item as? RuleItem.Blocks)?.rule ?: return@flatMapIndexed emptyList()
            rule.actions.mapIndexedNotNull { k, action ->
                if (action is Action.GoToScene && project.scene(action.sceneId) == null) {
                    BlockLocation(index, BlockPart.Action, k) to "Go to scene: that scene was removed. Choose another scene."
                } else {
                    null
                }
            }
        }

    /**
     * Adds a title screen as one undoable step and opens it (C9, C15). It is an ordinary scene made with catalog
     * operations: a background, the game's name and buttons whose rules go to other scenes. [layout] adds a Levels scene
     * with a button for every scene of the game, and a Shop and a How it works scene, each with a Back button. The title
     * screen becomes the start scene. Creators change everything afterwards with the inspector and rules.
     */
    fun addTitleScreen(layout: TitleLayout = TitleLayout.PlayOnly) {
        val current = project
        val batch = BatchBuilder(editor.saveSnapshot().nextId)
        val title = batch.scene("Title")
        batch.obj(title, "Background", placed(0.0, 0.0) + shape("rectangle", 16.0, 9.0, 0xFF1A237E, -1))
        batch.obj(title, "Title text", placed(0.0, -2.0) + text(current.name, 1.5))
        val start = current.scenes.first().id
        val play = batch.obj(title, "Play button", placed(0.0, 2.0) + shape("rectangle", 4.0, 1.5, 0xFF43A047, 1) + tag("play-button"))
        batch.obj(title, "Play label", placed(0.0, 0.0) + text("Play", 0.8), parent = play)
        batch.rules(title, play, listOf(goTo(start)))
        if (layout != TitleLayout.PlayOnly) {
            // The Play button moves up to make room for the others.
            batch.operations += EditOperation.SetProperty(title, play, "graphics:transform", "y", -0.2)
            batch.operations += EditOperation.SetProperty(title, play, "graphics:shape", "width", 4.4)
            batch.operations += EditOperation.SetProperty(title, play, "graphics:shape", "height", 1.1)
            val extras = mutableListOf("Levels")
            if (layout == TitleLayout.Full) extras += listOf("Shop", "How it works")
            val scenes = extras.map { it to batch.scene(it) }
            scenes.forEachIndexed { i, (name, id) -> batch.button(title, name, 0.0, 1.3 + 1.3 * i, name, id) }
            for ((name, id) in scenes) {
                when (name) {
                    "Levels" -> {
                        batch.backdrop(id, "Levels", 0xFF004D40)
                        // Narrow enough for a phone held upright: one column for up to 4 levels, then 2, then 3.
                        val count = current.scenes.size
                        val columns =
                            if (count <= 4) {
                                1
                            } else if (count <= 8) {
                                2
                            } else {
                                3
                            }
                        val width = listOf(4.4, 3.6, 2.6)[columns - 1]
                        val step = listOf(0.0, 4.0, 2.8)[columns - 1]
                        current.scenes.forEachIndexed { i, level ->
                            val x = step * (i % columns) - step * (columns - 1) / 2
                            val y = -1.8 + 1.3 * (i / columns)
                            batch.button(id, level.name, x, y, level.name, level.id, width = width, size = if (columns == 3) 0.45 else 0.6)
                        }
                    }

                    "Shop" -> {
                        batch.backdrop(id, "Shop", 0xFF4A148C)
                        batch.obj(id, "Shop text", placed(0.0, -1.0) + text("Put items for sale here.", 0.6))
                        batch.obj(id, "Shop hint", placed(0.0, 0.0) + text("Each item: a price, a Buy rule.", 0.5))
                    }

                    else -> {
                        batch.backdrop(id, "How it works", 0xFF263238)
                        batch.obj(id, "How text", placed(0.0, -1.0) + text("Explain how to play here.", 0.6))
                        batch.obj(id, "How hint", placed(0.0, 0.0) + text("Change these texts.", 0.5))
                    }
                }
                batch.button(id, "Back", 0.0, 3.4, "Back", title)
            }
        }
        batch.operations += EditOperation.MoveScene(title, 0)
        if (apply(EditOperation.Batch(batch.operations, "Add title screen")) is OperationResult.Applied) selectScene(title)
    }

    /** Opens scene [id] for editing. */
    fun selectScene(id: String) {
        if (project.scene(id) == null || id == sceneId) return
        sceneId = id
        selection = null
        dragging = null
    }

    /** Tags used in the current scene, for pickers. */
    fun tags(): List<String> =
        scene.objects
            .mapNotNull { (it.component("gameplay:tag")?.values?.get("tag") as? String)?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Counts loaded projects, so work tied to one project (an assistant proposal) can tell when it was replaced. */
    var projectGeneration by mutableIntStateOf(0)
        private set

    /** Replaces the workspace only after decoding succeeded; transient play/selection/camera state is reset. */
    fun loadProject(project: Project) {
        projectGeneration++
        play = null
        dragging = null
        selection = null
        editor.load(project)
        sceneId = project.scenes.first().id
        camera = CameraView(zoom = 1.0)
        message = project.capabilityProblems(engine.runtime).firstOrNull()
    }
}

/** Rounds to 0.01 world units so dragged positions stay tidy in the inspector. */
private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
