package dev.spawnkit.editing

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ValueKind

/** Outcome of turning catalog arguments into an [EditOperation]. */
public sealed interface BuildResult {
    /** The operation to apply. The editor still validates it when it is applied. */
    public data class Ready(
        /** Built operation. */
        public val operation: EditOperation,
    ) : BuildResult

    /** The arguments do not describe a possible change, with a plain-language explanation. */
    public data class Rejected(
        /** What is wrong. */
        public val message: String,
    ) : BuildResult
}

/**
 * Checked arguments of one [OperationSpec] call, with typed access for the spec's builder. Builders call [reject] to
 * refuse a change that the arguments' types allow but the project does not, for example a rule number past the end.
 */
public class OperationRequest internal constructor(
    /** Project and installed capabilities the call is checked against. */
    public val context: CatalogContext,
    private val values: Map<String, Any?>,
) {
    /** The current project. */
    public val project: Project get() = context.project

    /** Normalized value of argument [id], or `null` when it was omitted and has no default. */
    public fun valueOrNull(id: String): Any? = values[id]

    /** Normalized value of argument [id]. */
    public fun value(id: String): Any = values[id] ?: reject("Give a value for '$id'")

    /** Text, scene, object, component or property argument [id]. */
    public fun text(id: String): String = value(id) as String

    /** Text-like argument [id], or `null` when omitted. */
    public fun textOrNull(id: String): String? = valueOrNull(id) as String?

    /** Whole-number argument [id]. */
    public fun whole(id: String): Long = value(id) as Long

    /** Whole-number argument [id], or `null` when omitted. */
    public fun wholeOrNull(id: String): Long? = valueOrNull(id) as Long?

    /** Variant argument [id]. */
    public fun picked(id: String): Picked = value(id) as Picked

    /** List argument [id]; an omitted list is empty. */
    public fun list(id: String): List<Any> = (valueOrNull(id) as List<*>?).orEmpty().map { checkNotNull(it) }

    /** Components argument [id]; omitted means none. */
    public fun components(id: String): List<ComponentDoc> = list(id).map { it as ComponentDoc }

    /** The scene named by the `scene` argument. */
    public fun scene(): SceneDoc = project.scene(text(SCENE)) ?: reject("That scene no longer exists")

    /** The object named by argument [id] in the `scene` argument's scene. */
    public fun obj(id: String = "object"): ObjectDoc = scene().find(text(id)) ?: reject("That object no longer exists")

    /** Refuses the call with [message]. */
    public fun reject(message: String): Nothing = throw ArgumentProblem(message)
}

/**
 * Catalog metadata of one editor capability: how creators and agents call it, and how its arguments become an
 * [EditOperation]. Agent tools are derived from these entries ([toolDefinition]); the builder's controls call the
 * same operations, so neither path can do something the other cannot (ADR-0009).
 *
 * Parameters are checked in order; references come after what they refer to (`scene` before `object`, `component`
 * before `property` and `value`).
 */
public class OperationSpec(
    /** Stable snake_case ID, also the agent tool name. */
    public val id: String,
    /** Friendly name, plain-language help and category. */
    public val authoring: AuthoringInfo,
    /** Arguments in checking order. */
    public val parameters: List<Parameter>,
    /** Example requests this operation fulfils, shown as help and given to agents. */
    public val examples: List<String>,
    private val build: OperationRequest.() -> EditOperation,
) {
    init {
        require(id.matches(OPERATION_ID)) { "Invalid operation ID '$id'" }
        require(authoring.description.isNotBlank()) { "Operation '$id' needs help" }
        require(examples.isNotEmpty()) { "Operation '$id' needs examples" }
        require(parameters.map { it.id }.toSet().size == parameters.size) { "Duplicate parameter in '$id'" }
    }

    /** Checks [arguments] in [context] and builds the operation, or explains why it cannot. Nothing is applied. */
    public fun build(
        arguments: Map<String, Any?>,
        context: CatalogContext,
    ): BuildResult =
        when (val checked = checkArguments(parameters, arguments, context)) {
            is CheckedArguments.Invalid -> {
                BuildResult.Rejected(checked.message)
            }

            is CheckedArguments.Valid -> {
                try {
                    BuildResult.Ready(OperationRequest(context, checked.values).build())
                } catch (problem: ArgumentProblem) {
                    BuildResult.Rejected(problem.message ?: "This change is not possible")
                }
            }
        }
}

/** The operations creators and agents can perform, by ID. Installed packages contribute their own ([plus]). */
public class OperationCatalog(
    /** Entries in display order. */
    public val operations: List<OperationSpec>,
) {
    init {
        val ids = operations.map { it.id }
        require(ids.toSet().size == ids.size) { "Operation IDs must be unique: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}" }
    }

    /** The entry with [id], or `null`. */
    public operator fun get(id: String): OperationSpec? = operations.firstOrNull { it.id == id }

    /** This catalog followed by [other]'s entries. */
    public operator fun plus(other: OperationCatalog): OperationCatalog = OperationCatalog(operations + other.operations)

    /** Builds operation [id] from [arguments], or explains why it cannot. */
    public fun build(
        id: String,
        arguments: Map<String, Any?>,
        context: CatalogContext,
    ): BuildResult = get(id)?.build(arguments, context) ?: BuildResult.Rejected("There is no operation called '$id'")
}

/**
 * Catalog entries for the general editor operations that the builder offers as controls. Operations are added together
 * with their controls, never for agents alone. Grouping ([EditOperation.ReparentObject]) needs 2D placement, so its
 * entry comes from the standard engine's `ObjectOperations`, and rule operations come from the package that defines
 * rule blocks.
 */
public object EditingOperations {
    /** The entries. */
    public val catalog: OperationCatalog by lazy {
        OperationCatalog(
            listOf(
                renameProject,
                addScene,
                renameScene,
                removeScene,
                moveScene,
                addObject,
                removeObject,
                renameObject,
                duplicateObject,
                addComponent,
                removeComponent,
                setProperty,
            ),
        )
    }

    private val renameProject =
        OperationSpec(
            "rename_project",
            AuthoringInfo("Rename the game", "Changes the game's name, shown to players and in the project list.", PROJECT),
            listOf(nameParameter("New name of the game")),
            listOf("Call my game Coin Rush", "Rename the game to Space Hop"),
        ) { EditOperation.RenameProject(text("name")) }

    private val addScene =
        OperationSpec(
            "add_scene",
            AuthoringInfo("Add a scene", "Adds an empty scene at the end, for example a new level or a title screen.", SCENES),
            listOf(nameParameter("Name of the new scene")),
            listOf("Add a scene called Level 2", "Add a title screen scene"),
        ) { EditOperation.AddScene(text("name")) }

    private val renameScene =
        OperationSpec(
            "rename_scene",
            AuthoringInfo("Rename a scene", "Changes a scene's name in the scene list.", SCENES),
            listOf(sceneParameter(), nameParameter("New name of the scene")),
            listOf("Call the first scene Forest"),
        ) { EditOperation.RenameScene(text(SCENE), text("name"), "Rename scene ${scene().name}") }

    private val removeScene =
        OperationSpec(
            "remove_scene",
            AuthoringInfo("Delete a scene", "Deletes a scene with everything in it. A game always keeps at least one scene.", SCENES),
            listOf(sceneParameter()),
            listOf("Delete the bonus level"),
        ) { EditOperation.RemoveScene(text(SCENE), "Delete scene ${scene().name}") }

    private val moveScene =
        OperationSpec(
            "move_scene",
            AuthoringInfo(
                "Move a scene",
                "Moves a scene to another position in the scene list. Position 1 is the start scene, which players see first.",
                SCENES,
            ),
            listOf(
                sceneParameter(),
                Parameter(
                    "to",
                    "To",
                    "New position, starting at 1; 1 makes it the start scene.",
                    ParameterType.Value(ValueKind.WholeNumber(1, MAX_SCENES)),
                ),
            ),
            listOf("Make the title screen the start scene", "Move Level 3 before Level 2"),
        ) {
            val to = whole("to").toInt()
            val label = if (to == 1) "Make ${scene().name} the start scene" else "Move scene ${scene().name}"
            EditOperation.MoveScene(text(SCENE), to - 1, label)
        }

    private val addObject =
        OperationSpec(
            "add_object",
            AuthoringInfo(
                "Add an object",
                "Adds an object to a scene with the given components and property values; omitted properties keep their " +
                    "defaults. Objects show nothing until they have a placement and a look, so add those components too.",
                OBJECTS,
            ),
            listOf(
                sceneParameter(),
                nameParameter("Name of the new object"),
                Parameter(
                    "components",
                    "Components",
                    "Components to give the object, each with optional property values.",
                    ParameterType.Components(),
                    default = emptyList<Any>(),
                    required = false,
                ),
            ),
            listOf("Add a coin at 3, 2", "Add a red box called Wall"),
        ) { EditOperation.AddObject(text(SCENE), text("name"), components = components("components")) }

    private val removeObject =
        OperationSpec(
            "remove_object",
            AuthoringInfo("Delete an object", "Deletes an object together with all objects inside it.", OBJECTS),
            listOf(sceneParameter(), objectParameter()),
            listOf("Delete the second coin", "Remove the wall"),
        ) { EditOperation.RemoveObject(text(SCENE), text(OBJECT), "Delete ${obj().name}") }

    private val renameObject =
        OperationSpec(
            "rename_object",
            AuthoringInfo("Rename an object", "Changes an object's name in the scene list.", OBJECTS),
            listOf(sceneParameter(), objectParameter(), nameParameter("New name of the object")),
            listOf("Call the blue box Hero"),
        ) { EditOperation.RenameObject(text(SCENE), text(OBJECT), text("name")) }

    private val duplicateObject =
        OperationSpec(
            "duplicate_object",
            AuthoringInfo(
                "Duplicate an object",
                "Copies an object and everything inside it, with its components and rules, placed right after it.",
                OBJECTS,
            ),
            listOf(sceneParameter(), objectParameter()),
            listOf("Make another coin like this one"),
        ) { EditOperation.DuplicateObject(text(SCENE), text(OBJECT)) }

    private val addComponent =
        OperationSpec(
            "add_component",
            AuthoringInfo("Add a component", "Gives an object an installed component with its default values.", COMPONENTS),
            listOf(sceneParameter(), objectParameter(), componentParameter()),
            listOf("Give the score a number", "Add text to the sign"),
        ) {
            val descriptor = context.runtime.catalog.first { it.component.id == text(COMPONENT) }
            EditOperation.AddComponent(text(SCENE), text(OBJECT), text(COMPONENT), "Add ${descriptor.component.authoring.name}")
        }

    private val removeComponent =
        OperationSpec(
            "remove_component",
            AuthoringInfo("Remove a component", "Removes a component and its values from an object.", COMPONENTS),
            listOf(sceneParameter(), objectParameter(), componentParameter()),
            listOf("Take the tag off the box"),
        ) {
            val descriptor = context.runtime.catalog.first { it.component.id == text(COMPONENT) }
            EditOperation.RemoveComponent(text(SCENE), text(OBJECT), text(COMPONENT), "Remove ${descriptor.component.authoring.name}")
        }

    private val setProperty =
        OperationSpec(
            "set_property",
            AuthoringInfo(
                "Change a property",
                "Sets one property of a component the object already has, such as its position, size, color or text.",
                COMPONENTS,
            ),
            listOf(
                sceneParameter(),
                objectParameter(),
                componentParameter(),
                Parameter(
                    "property",
                    "Property",
                    "ID of the property to change, from the component's description.",
                    ParameterType.Property,
                ),
                Parameter("value", "Value", "New value; its kind and limits come from the property.", ParameterType.PropertyValue),
            ),
            listOf("Move the coin to x 4", "Make the box red", "Set the score text to Score: 0"),
        ) {
            val descriptor = context.runtime.catalog.first { it.component.id == text(COMPONENT) }
            val property = checkNotNull(descriptor.property(text("property")))
            EditOperation.SetProperty(
                text(SCENE),
                text(OBJECT),
                text(COMPONENT),
                property.id,
                value("value"),
                "Change ${property.authoring.name} of ${obj().name}",
            )
        }
}

/** The `scene` argument shared by operations on scene content. */
public fun sceneParameter(): Parameter = Parameter(SCENE, "Scene", "ID of the scene, for example scene-1.", ParameterType.Scene)

/** The `object` argument shared by operations on one object. */
public fun objectParameter(): Parameter =
    Parameter(OBJECT, "Object", "ID of the object in that scene, for example obj-3.", ParameterType.Object)

private fun componentParameter() =
    Parameter(COMPONENT, "Component", "ID of an installed component, for example graphics:shape.", ParameterType.Component)

private fun nameParameter(help: String) = Parameter("name", "Name", "$help, up to 80 characters.", ParameterType.Value(ValueKind.Text(80)))

private const val OBJECT = "object"
private const val PROJECT = "Project"
private const val SCENES = "Scenes"
private const val MAX_SCENES = 100L
private const val OBJECTS = "Objects"
private const val COMPONENTS = "Components"
private val OPERATION_ID = Regex("[a-z][a-z0-9_]*")
