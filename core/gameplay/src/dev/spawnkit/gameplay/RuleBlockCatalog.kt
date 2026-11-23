package dev.spawnkit.gameplay

import dev.spawnkit.editing.CheckedArguments
import dev.spawnkit.editing.Parameter
import dev.spawnkit.editing.ParameterType
import dev.spawnkit.editing.Picked
import dev.spawnkit.editing.Suggestions
import dev.spawnkit.editing.Variant
import dev.spawnkit.editing.checkArguments
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ChoiceOption
import dev.spawnkit.runtime.ValueKind

/** Outcome of creating a block from arguments. */
public sealed interface BlockResult<out B : Any> {
    /** The block was created. */
    public data class Created<B : Any>(
        /** The block. */
        public val block: B,
    ) : BlockResult<B>

    /** The arguments do not describe a block, with a plain-language explanation. */
    public data class Invalid(
        /** What is wrong. */
        public val message: String,
    ) : BlockResult<Nothing>
}

/**
 * Catalog metadata of one rule block: its name, help, parameters with limits, and examples, plus conversion between
 * arguments and the typed block. The builder's block dialog and the agent tools are both generated from it.
 */
public class BlockSpec<B : Any> internal constructor(
    /** Stable ID, used by the builder and agents (for example `Touching`). */
    public val id: String,
    /** Whether this is a condition ("When" / "And if") or an action ("Do"). */
    public val part: BlockPart,
    /** Friendly name, plain-language help and category. */
    public val authoring: AuthoringInfo,
    /** Arguments with limits and starting values. */
    public val parameters: List<Parameter>,
    /** Example uses in the creator's words. */
    public val examples: List<String>,
    private val make: (Map<String, Any?>) -> B,
    private val read: (B) -> Map<String, Any?>,
) {
    /** This block as an alternative of [RuleBlockCatalog.blockType] and its narrower variants. */
    public val variant: Variant = Variant(id, authoring, parameters, examples)

    /** Checks [arguments] (omitted ones take their defaults) and creates the block. */
    public fun create(arguments: Map<String, Any?> = emptyMap()): BlockResult<B> =
        when (val checked = checkArguments(parameters, arguments)) {
            is CheckedArguments.Invalid -> {
                BlockResult.Invalid(checked.message)
            }

            is CheckedArguments.Valid -> {
                try {
                    BlockResult.Created(make(checked.values))
                } catch (problem: IllegalArgumentException) {
                    BlockResult.Invalid(problem.message ?: "This block is not possible")
                }
            }
        }

    /** The arguments that [create] turns back into [block]. */
    public fun arguments(block: B): Map<String, Any?> = read(block)

    /** Starting values of the parameters, used for a new block before the creator changes anything. */
    public fun defaults(): Map<String, Any?> = parameters.mapNotNull { p -> p.default?.let { p.id to it } }.toMap()
}

/**
 * The catalog of the standard rule blocks (ADR-0009). Every [Condition] and [Action] has exactly one [BlockSpec];
 * the builder generates its block controls from these entries and agents receive them as tool parameters.
 */
public object RuleBlockCatalog {
    /** "Which object" for number and text blocks: this object, or the first object with a tag. Values are [Picked]. */
    public val targetType: ParameterType.OneOf =
        ParameterType.OneOf(
            "kind",
            listOf(
                Variant(SELF, AuthoringInfo("This object", "The object the rule belongs to.")),
                Variant(
                    TAGGED,
                    AuthoringInfo("Object with a tag", "The first object that has the tag, for example the score display."),
                    listOf(tagParameter("Tag of the object.")),
                ),
            ),
        )

    /** Where a copy appears: this object, an object with a tag, a fixed point or a random spot. */
    public val placeType: ParameterType.OneOf =
        ParameterType.OneOf(
            "kind",
            listOf(
                Variant(SELF, AuthoringInfo("This object", "Where the object with the rule is.")),
                Variant(
                    TAGGED,
                    AuthoringInfo("Object with a tag", "Where the first object with the tag is."),
                    listOf(tagParameter("Tag of the object.")),
                ),
                Variant(
                    POSITION,
                    AuthoringInfo("A point", "A fixed point in the scene."),
                    listOf(coordinate("x", "X", "Across; larger is further right."), coordinate("y", "Y", "Down; larger is lower.")),
                ),
                Variant(
                    RANDOM,
                    AuthoringInfo("A random spot", "A random point in a box; the same game always picks the same spots."),
                    listOf(
                        wholeCoordinate("min_x", "Smallest x", -10L),
                        wholeCoordinate("max_x", "Largest x", 10L),
                        wholeCoordinate("min_y", "Smallest y", -5L),
                        wholeCoordinate("max_y", "Largest y", -5L),
                    ),
                ),
            ),
        )

    /** Every condition block, in palette order. */
    public val conditions: List<BlockSpec<Condition>> =
        listOf(
            condition(
                "Always",
                AuthoringInfo(
                    "Every moment",
                    "True on every moment of play, 60 times a second. Use it for things that keep happening, such as moving.",
                    TIME,
                ),
                listOf("Every moment, move right"),
            ) { Condition.Always },
            condition(
                "GameStarts",
                AuthoringInfo("Game starts", "True once, on the first moment of play. Use it to set up starting values.", TIME),
                listOf("When the game starts, set the score to 0"),
            ) { Condition.GameStarts },
            conditionSpec<Condition.EverySeconds>(
                "EverySeconds",
                AuthoringInfo("Every … seconds", "True once at the end of every period, starting when play starts.", TIME),
                listOf(
                    Parameter(
                        "seconds",
                        "Seconds",
                        "Length of the period, from 1 second to one day.",
                        ParameterType.Value(ValueKind.WholeNumber(1, MAX_SECONDS, unit = "seconds")),
                        default = 1L,
                    ),
                ),
                listOf("Every 2 seconds, add 1 to the score"),
                { Condition.EverySeconds(it.whole("seconds")) },
                { mapOf("seconds" to it.seconds) },
            ),
            conditionSpec<Condition.Touching>(
                "Touching",
                AuthoringInfo("Touching", "True while this object overlaps an object with the tag.", OBJECTS),
                listOf(tagParameter("Tag of the other object, for example player.")),
                listOf("When the coin touches the player, remove the coin"),
                { Condition.Touching(it.text("tag")) },
                { mapOf("tag" to it.tag) },
            ),
            condition(
                "Tapped",
                AuthoringInfo("Tapped", "True when a finger or the mouse goes down on this object.", OBJECTS),
                listOf("When the box is tapped, remove it"),
            ) { Condition.Tapped },
            conditionSpec<Condition.KeyHeld>(
                "KeyHeld",
                AuthoringInfo("Holding a key", "True while the player holds the key or on-screen button for the action.", INPUT),
                listOf(actionParameter(StandardActions.RIGHT)),
                listOf("While holding Right, move right"),
                { Condition.KeyHeld(it.text("action")) },
                { mapOf("action" to it.action) },
            ),
            conditionSpec<Condition.KeyPressed>(
                "KeyPressed",
                AuthoringInfo("Pressing a key", "True on the moment the key or on-screen button for the action goes down.", INPUT),
                listOf(actionParameter(StandardActions.JUMP)),
                listOf("When Jump is pressed, add 1 to the score"),
                { Condition.KeyPressed(it.text("action")) },
                { mapOf("action" to it.action) },
            ),
            conditionSpec<Condition.NumberIs>(
                "NumberIs",
                AuthoringInfo("Number is", "Compares an object's number with a value.", NUMBERS),
                listOf(targetParameter(), comparisonParameter(), valueParameter("Value to compare with.", 0L, -MAX_VALUE)),
                listOf("If the score is at least 10, show You win"),
                { Condition.NumberIs(it.target(), it.comparison(), it.whole("value")) },
                { mapOf(TARGET to picked(it.target), COMPARISON to it.comparison.name, "value" to it.value) },
            ),
            conditionSpec<Condition.CountIs>(
                "CountIs",
                AuthoringInfo("Count of objects", "Compares how many objects have the tag with a value.", OBJECTS),
                listOf(
                    tagParameter("Tag to count."),
                    comparisonParameter(),
                    valueParameter("Number of objects to compare with.", 0L, 0L),
                ),
                listOf("When no coins are left, set the text to You win"),
                { Condition.CountIs(it.text("tag"), it.comparison(), it.whole("value")) },
                { mapOf("tag" to it.tag, COMPARISON to it.comparison.name, "value" to it.value) },
            ),
            conditionSpec<Condition.LeftArea>(
                "LeftArea",
                AuthoringInfo(
                    "Leaves the play area",
                    "True when this object is wholly outside a box around the camera. Use it to remove shots and enemies that fly away.",
                    OBJECTS,
                ),
                listOf(areaParameter("width", "Width", 40.0), areaParameter("height", "Height", 24.0)),
                listOf("When a shot leaves the play area, remove it"),
                { Condition.LeftArea(it.getValue("width") as Double, it.getValue("height") as Double) },
                { mapOf("width" to it.width, "height" to it.height) },
            ),
            condition(
                "OnGround",
                AuthoringInfo(
                    "On the ground",
                    "True while this object's Body stands on a Solid object, such as the floor or a platform.",
                    MOVEMENT,
                ),
                listOf("When pressing Jump and on the ground, jump", "While on the ground, show Ready"),
            ) { Condition.OnGround },
        )

    private fun areaParameter(
        id: String,
        name: String,
        default: Double,
    ) = Parameter(
        id,
        name,
        "$name of the play area in world units.",
        ParameterType.Value(ValueKind.Decimal(1.0, MAX_COORDINATE)),
        default = default,
    )

    /** Every action block, in palette order. */
    public val actions: List<BlockSpec<Action>> =
        listOf(
            actionSpec<Action.Move>(
                "Move",
                AuthoringInfo("Move", "Moves this object in a direction at a steady speed while the rule is true.", MOVEMENT),
                listOf(
                    Parameter(
                        "direction",
                        "Direction",
                        "Which way to move; up is towards the top of the screen.",
                        ParameterType.Value(ValueKind.Choice(Direction.entries.map { ChoiceOption(it.name, it.label) })),
                        default = Direction.Right.name,
                    ),
                    Parameter(
                        "speed",
                        "Speed",
                        "World units per second, from 0 to 1000.",
                        ParameterType.Value(ValueKind.Decimal(0.0, MAX_SPEED, unit = "units per second")),
                        default = 3.0,
                    ),
                ),
                listOf("Move left at 5 per second while holding Left"),
                { Action.Move(Direction.valueOf(it.text("direction")), it.getValue("speed") as Double) },
                { mapOf("direction" to it.direction.name, "speed" to it.speed) },
            ),
            actionSpec<Action.Jump>(
                "Jump",
                AuthoringInfo(
                    "Jump",
                    "If this object's Body is on the ground, it jumps up at the speed; gravity brings it back down. Higher speeds jump higher.",
                    MOVEMENT,
                ),
                listOf(
                    Parameter(
                        "speed",
                        "Speed",
                        "Speed up when leaving the ground, in units per second. 15 jumps about 2 units with normal gravity.",
                        ParameterType.Value(ValueKind.Decimal(0.0, MAX_SPEED, unit = "units per second")),
                        default = 15.0,
                    ),
                ),
                listOf("When Jump is pressed, jump at 15 per second"),
                { Action.Jump(it.getValue("speed") as Double) },
                { mapOf("speed" to it.speed) },
            ),
            actionSpec<Action.SetSpeed>(
                "SetSpeed",
                AuthoringInfo(
                    "Set speed",
                    "Gives this object's Body a speed in a direction, which it keeps until something stops it. Use it for launch pads, knock-back and dashes.",
                    MOVEMENT,
                ),
                listOf(
                    Parameter(
                        "direction",
                        "Direction",
                        "Which way; only the speed along this direction changes.",
                        ParameterType.Value(ValueKind.Choice(Direction.entries.map { ChoiceOption(it.name, it.label) })),
                        default = Direction.Up.name,
                    ),
                    Parameter(
                        "speed",
                        "Speed",
                        "World units per second, from 0 to 1000.",
                        ParameterType.Value(ValueKind.Decimal(0.0, MAX_SPEED, unit = "units per second")),
                        default = 20.0,
                    ),
                ),
                listOf("When touching a spring, set speed up to 25", "When touching an enemy, set speed left to 8"),
                { Action.SetSpeed(Direction.valueOf(it.text("direction")), it.getValue("speed") as Double) },
                { mapOf("direction" to it.direction.name, "speed" to it.speed) },
            ),
            actionSpec<Action.ChangeNumber>(
                "ChangeNumber",
                AuthoringInfo("Add to a number", "Adds the amount to an object's number; a negative amount takes it away.", NUMBERS),
                listOf(
                    targetParameter(),
                    valueParameter("Amount to add; negative to take away.", 1L, -MAX_VALUE, id = "amount", name = "Amount"),
                ),
                listOf("Add 1 to the score", "Take 1 life away"),
                { Action.ChangeNumber(it.target(), it.whole("amount")) },
                { mapOf(TARGET to picked(it.target), "amount" to it.by) },
            ),
            actionSpec<Action.SetNumber>(
                "SetNumber",
                AuthoringInfo("Set a number", "Sets an object's number to a value.", NUMBERS),
                listOf(targetParameter(), valueParameter("New value.", 0L, -MAX_VALUE)),
                listOf("Set the score to 0"),
                { Action.SetNumber(it.target(), it.whole("value")) },
                { mapOf(TARGET to picked(it.target), "value" to it.to) },
            ),
            actionSpec<Action.SetRandomNumber>(
                "SetRandomNumber",
                AuthoringInfo(
                    "Set a random number",
                    "Sets an object's number to a random whole number in a range, like rolling a dice. The same game always rolls the same way.",
                    NUMBERS,
                ),
                listOf(
                    targetParameter(),
                    valueParameter("Smallest possible number.", 1L, -MAX_VALUE, id = "min", name = "From"),
                    valueParameter("Largest possible number.", 6L, -MAX_VALUE, id = "max", name = "To"),
                ),
                listOf("Roll a dice from 1 to 6", "Pick a random lane from 1 to 3"),
                { Action.SetRandomNumber(it.target(), it.whole("min"), it.whole("max")) },
                { mapOf(TARGET to picked(it.target), "min" to it.min, "max" to it.max) },
            ),
            actionSpec<Action.AddNumber>(
                "AddNumber",
                AuthoringInfo(
                    "Add one number to another",
                    "Adds another object's number to this number, such as adding Income to Coins every second.",
                    NUMBERS,
                ),
                listOf(
                    targetParameter(),
                    Parameter(
                        "from",
                        "Number to add",
                        "The object whose number is added.",
                        targetType,
                        default = Picked(TAGGED, mapOf("tag" to "income")),
                        required = true,
                    ),
                ),
                listOf("Every second, add Income to Coins"),
                { Action.AddNumber(it.target(), it.target("from")) },
                { mapOf(TARGET to picked(it.target), "from" to picked(it.from)) },
            ),
            actionSpec<Action.SetText>(
                "SetText",
                AuthoringInfo("Set text", "Changes the text an object shows.", TEXT),
                listOf(
                    targetParameter(),
                    Parameter(
                        "text",
                        "Text",
                        "Text to show.",
                        ParameterType.Value(ValueKind.Text(MAX_TEXT)),
                        default = "",
                        required = false,
                    ),
                ),
                listOf("Show Game over on the sign"),
                { Action.SetText(it.target(), it.text("text")) },
                { mapOf(TARGET to picked(it.target), "text" to it.text) },
            ),
            actionSpec<Action.ShowNumber>(
                "ShowNumber",
                AuthoringInfo("Show a number", "Sets an object's text to the words followed by its number, such as \"Score: 3\".", NUMBERS),
                listOf(
                    targetParameter(),
                    Parameter(
                        "prefix",
                        "Text before the number",
                        "Words shown before the number.",
                        ParameterType.Value(ValueKind.Text(MAX_PREFIX)),
                        default = "Score: ",
                        required = false,
                    ),
                ),
                listOf("Show the score as Score: and the number"),
                { Action.ShowNumber(it.target(), it.text("prefix")) },
                { mapOf(TARGET to picked(it.target), "prefix" to it.prefix) },
            ),
            actionSpec<Action.RemoveSelf>(
                "RemoveSelf",
                AuthoringInfo("Remove this object", "Removes this object and everything inside it at the end of the moment.", OBJECTS),
                emptyList(),
                listOf("Remove the coin when it is collected"),
                { Action.RemoveSelf },
                { emptyMap() },
            ),
            actionSpec<Action.Spawn>(
                "Spawn",
                AuthoringInfo(
                    "Spawn a copy",
                    "Makes a copy of the object with the tag, best one marked Template, and the copy runs its own rules. Use it for enemies, falling things and shots.",
                    OBJECTS,
                ),
                listOf(
                    tagParameter("Tag of the object to copy."),
                    Parameter("place", "Where", "Where the copy appears.", placeType, default = Picked(SELF), required = false),
                    Parameter(
                        "direction",
                        "Keeps moving",
                        "Direction the copy keeps moving in, or None.",
                        ParameterType.Value(
                            ValueKind.Choice(
                                listOf(ChoiceOption(NONE, "None")) + Direction.entries.map { ChoiceOption(it.name, it.label) },
                            ),
                        ),
                        default = NONE,
                    ),
                    Parameter(
                        "speed",
                        "Speed",
                        "Units per second, from 0 to ${MAX_SPEED.toLong()}.",
                        ParameterType.Value(ValueKind.Decimal(0.0, MAX_SPEED)),
                        default = 0.0,
                    ),
                ),
                listOf(
                    "Every 2 seconds, spawn an enemy at a random spot moving down",
                    "When Jump is pressed, spawn a shot at the ship moving up",
                ),
                {
                    val direction = it.text("direction").takeIf { d -> d != NONE }?.let(Direction::valueOf)
                    Action.Spawn(it.text("tag"), it.place(), direction, it.getValue("speed") as Double)
                },
                { mapOf("tag" to it.tag, "place" to picked(it.place), "direction" to (it.direction?.name ?: NONE), "speed" to it.speed) },
            ),
            actionSpec<Action.GoToScene>(
                "GoToScene",
                AuthoringInfo(
                    "Go to scene",
                    "Switches the game to another scene, which starts fresh. Use it for a Play button on a title screen, a next level or a restart.",
                    SCENES,
                ),
                listOf(
                    Parameter(
                        "scene",
                        "Scene",
                        "ID of the scene to go to, for example scene-2.",
                        ParameterType.Value(ValueKind.Text(MAX_SCENE_ID), Suggestions.SCENES),
                        default = "",
                        required = false,
                    ),
                ),
                listOf("When the Play button is tapped, go to the first level", "Restart the level when the player touches lava"),
                { Action.GoToScene(it.text("scene")) },
                { mapOf("scene" to it.sceneId) },
            ),
        )

    /** Any condition block; values are [Picked] with the block ID in `block`. */
    public val conditionType: ParameterType.OneOf = ParameterType.OneOf(BLOCK, conditions.map { it.variant })

    /** Any action block. */
    public val actionType: ParameterType.OneOf = ParameterType.OneOf(BLOCK, actions.map { it.variant })

    /** Any block, condition or action. */
    public val blockType: ParameterType.OneOf = ParameterType.OneOf(BLOCK, (conditions + actions).map { it.variant })

    /** The entry describing [condition]. */
    public fun spec(condition: Condition): BlockSpec<Condition> = conditions.first { it.id == idOf(condition) }

    /** The entry describing [action]. */
    public fun spec(action: Action): BlockSpec<Action> = actions.first { it.id == idOf(action) }

    /** [condition] as a catalog choice. */
    public fun picked(condition: Condition): Picked = spec(condition).let { Picked(it.id, it.arguments(condition)) }

    /** [action] as a catalog choice. */
    public fun picked(action: Action): Picked = spec(action).let { Picked(it.id, it.arguments(action)) }

    /** The condition [picked] describes, or why it cannot be one. */
    public fun condition(picked: Picked): BlockResult<Condition> =
        conditions.firstOrNull { it.id == picked.variant }?.create(picked.arguments)
            ?: BlockResult.Invalid(notA(picked.variant, "a condition", "When or And if", conditions))

    /** The action [picked] describes, or why it cannot be one. */
    public fun action(picked: Picked): BlockResult<Action> =
        actions.firstOrNull { it.id == picked.variant }?.create(picked.arguments)
            ?: BlockResult.Invalid(notA(picked.variant, "an action", "Do", actions))

    private fun notA(
        id: String,
        what: String,
        part: String,
        specs: List<BlockSpec<*>>,
    ): String = "$id is not $what block, so it cannot be a $part block. Use one of: ${specs.joinToString { it.id }}"

    /** A [Target] as a [targetType] choice. */
    public fun picked(target: Target): Picked =
        when (target) {
            Target.Self -> Picked(SELF)
            is Target.Tagged -> Picked(TAGGED, mapOf("tag" to target.tag))
        }

    private fun idOf(condition: Condition): String =
        when (condition) {
            Condition.Always -> "Always"
            Condition.GameStarts -> "GameStarts"
            is Condition.EverySeconds -> "EverySeconds"
            is Condition.Touching -> "Touching"
            Condition.Tapped -> "Tapped"
            is Condition.KeyHeld -> "KeyHeld"
            is Condition.KeyPressed -> "KeyPressed"
            is Condition.NumberIs -> "NumberIs"
            is Condition.CountIs -> "CountIs"
            is Condition.LeftArea -> "LeftArea"
            Condition.OnGround -> "OnGround"
        }

    private fun idOf(action: Action): String =
        when (action) {
            is Action.Move -> "Move"
            is Action.ChangeNumber -> "ChangeNumber"
            is Action.SetNumber -> "SetNumber"
            is Action.SetRandomNumber -> "SetRandomNumber"
            is Action.AddNumber -> "AddNumber"
            is Action.SetText -> "SetText"
            is Action.ShowNumber -> "ShowNumber"
            Action.RemoveSelf -> "RemoveSelf"
            is Action.Spawn -> "Spawn"
            is Action.Jump -> "Jump"
            is Action.SetSpeed -> "SetSpeed"
            is Action.GoToScene -> "GoToScene"
        }

    private fun condition(
        id: String,
        authoring: AuthoringInfo,
        examples: List<String>,
        make: () -> Condition,
    ) = BlockSpec(id, BlockPart.Condition, authoring, emptyList(), examples, { make() }, { emptyMap() })

    /** A condition entry for blocks of type [T]; [spec] finds entries by ID, so [read] only receives [T]. */
    private inline fun <reified T : Condition> conditionSpec(
        id: String,
        authoring: AuthoringInfo,
        parameters: List<Parameter>,
        examples: List<String>,
        noinline make: (Map<String, Any?>) -> T,
        noinline read: (T) -> Map<String, Any?>,
    ): BlockSpec<Condition> = BlockSpec(id, BlockPart.Condition, authoring, parameters, examples, make) { read(it as T) }

    /** An action entry for blocks of type [T]. */
    private inline fun <reified T : Action> actionSpec(
        id: String,
        authoring: AuthoringInfo,
        parameters: List<Parameter>,
        examples: List<String>,
        noinline make: (Map<String, Any?>) -> T,
        noinline read: (T) -> Map<String, Any?>,
    ): BlockSpec<Action> = BlockSpec(id, BlockPart.Action, authoring, parameters, examples, make) { read(it as T) }

    /** The arguments of [place] as the catalog's `place` parameter. */
    public fun picked(place: SpawnPlace): Picked =
        when (place) {
            SpawnPlace.Self -> {
                Picked(SELF)
            }

            is SpawnPlace.Tagged -> {
                Picked(TAGGED, mapOf("tag" to place.tag))
            }

            is SpawnPlace.Position -> {
                Picked(POSITION, mapOf("x" to place.x, "y" to place.y))
            }

            is SpawnPlace.RandomArea -> {
                Picked(
                    RANDOM,
                    mapOf(
                        "min_x" to place.minX,
                        "max_x" to place.maxX,
                        "min_y" to place.minY,
                        "max_y" to place.maxY,
                    ),
                )
            }
        }

    private fun Map<String, Any?>.place(): SpawnPlace {
        val picked = getValue("place") as Picked
        val args = picked.arguments
        return when (picked.variant) {
            TAGGED -> {
                SpawnPlace.Tagged(args.getValue("tag") as String)
            }

            POSITION -> {
                SpawnPlace.Position(args.getValue("x") as Double, args.getValue("y") as Double)
            }

            RANDOM -> {
                SpawnPlace.RandomArea(
                    args.getValue("min_x") as Long,
                    args.getValue("max_x") as Long,
                    args.getValue("min_y") as Long,
                    args.getValue("max_y") as Long,
                )
            }

            else -> {
                SpawnPlace.Self
            }
        }
    }

    private fun coordinate(
        id: String,
        name: String,
        help: String,
    ) = Parameter(id, name, help, ParameterType.Value(ValueKind.Decimal(-MAX_COORDINATE, MAX_COORDINATE)), default = 0.0)

    private fun wholeCoordinate(
        id: String,
        name: String,
        default: Long,
    ) = Parameter(
        id,
        name,
        "$name of the box, in world units.",
        ParameterType.Value(ValueKind.WholeNumber(-MAX_COORDINATE.toLong(), MAX_COORDINATE.toLong())),
        default = default,
    )

    private fun tagParameter(help: String) =
        Parameter("tag", "Tag", help, ParameterType.Value(ValueKind.Text(MAX_TAG), Suggestions.TAGS), default = "", required = true)

    private fun targetParameter() =
        Parameter(TARGET, "Which object", "The object whose number or text changes.", targetType, default = Picked(SELF), required = false)

    private fun actionParameter(default: String) =
        Parameter(
            "action",
            "Key",
            "Input action; keyboards, game pads and on-screen buttons all trigger it.",
            ParameterType.Value(ValueKind.Choice(StandardActions.ALL.map { (id, name) -> ChoiceOption(id, name) })),
            default = default,
        )

    private fun comparisonParameter() =
        Parameter(
            COMPARISON,
            "Comparison",
            "How to compare the number with the value.",
            ParameterType.Value(ValueKind.Choice(Comparison.entries.map { ChoiceOption(it.name, it.symbol) })),
            default = Comparison.Equal.name,
        )

    private fun valueParameter(
        help: String,
        default: Long,
        min: Long,
        id: String = "value",
        name: String = "Value",
    ) = Parameter(id, name, help, ParameterType.Value(ValueKind.WholeNumber(min, MAX_VALUE)), default = default)

    private fun Map<String, Any?>.text(id: String): String = getValue(id) as String

    private fun Map<String, Any?>.whole(id: String): Long = getValue(id) as Long

    private fun Map<String, Any?>.comparison(): Comparison = Comparison.valueOf(text(COMPARISON))

    private fun Map<String, Any?>.target(id: String = TARGET): Target {
        val picked = getValue(id) as Picked
        return if (picked.variant == SELF) Target.Self else Target.Tagged(picked.arguments.getValue("tag") as String)
    }

    private const val BLOCK = "block"
    private const val TARGET = "target"
    private const val COMPARISON = "comparison"
    private const val SELF = "self"
    private const val TAGGED = "tagged"
    private const val POSITION = "position"
    private const val RANDOM = "random"
    private const val NONE = "None"
    private const val MAX_COORDINATE = 100_000.0
    private const val TIME = "Time"
    private const val OBJECTS = "Objects"
    private const val INPUT = "Input"
    private const val NUMBERS = "Numbers"
    private const val MOVEMENT = "Movement"
    private const val TEXT = "Text"
    private const val SCENES = "Scenes"
    private const val MAX_SCENE_ID = 64

    /** Largest magnitude of numbers in blocks. */
    public const val MAX_VALUE: Long = 1_000_000_000L
    private const val MAX_SECONDS = 86_400L
    private const val MAX_SPEED = 1_000.0
    private const val MAX_TAG = 100
    private const val MAX_TEXT = 1_000
    private const val MAX_PREFIX = 200
}
