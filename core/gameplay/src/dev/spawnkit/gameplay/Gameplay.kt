package dev.spawnkit.gameplay

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.graphics.Text2D
import dev.spawnkit.program.BoolType
import dev.spawnkit.program.BoolValue
import dev.spawnkit.program.EntityType
import dev.spawnkit.program.EntityValue
import dev.spawnkit.program.IntegerType
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.ProgramHostBuilder
import dev.spawnkit.program.ProgramHostException
import dev.spawnkit.program.TextType
import dev.spawnkit.program.TextValue
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.InputView
import dev.spawnkit.runtime.ResourceType
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.SeededRandom
import dev.spawnkit.runtime.StateCaptureContext
import dev.spawnkit.runtime.StateCodec
import dev.spawnkit.runtime.StateRestoreContext
import dev.spawnkit.runtime.StateValue
import dev.spawnkit.runtime.ValueKind
import dev.spawnkit.runtime.World
import kotlin.math.abs

/** A group name such as `player`, `coin` or `enemy`. Rules find and test objects by tag. */
public data class Tag(
    /** Group name; compared ignoring case and surrounding spaces. */
    public val name: String = "",
)

/**
 * A whole number kept by an object, such as a score, coins or lives. With [keep], it is a game-wide value: when the
 * game goes to another scene, it carries over to the object with the same tag there (see
 * [StandardEngine.carryKeptNumbers]).
 */
public data class NumberValue(
    /** Current value. */
    public val value: Long = 0,
    /** Whether the value is kept across scenes. */
    public val keep: Boolean = false,
)

/** An axis-aligned box in world units, used for touching and tapping. */
public data class Bounds(
    /** Left edge. */
    public val left: Double,
    /** Top edge (y points down). */
    public val top: Double,
    /** Right edge. */
    public val right: Double,
    /** Bottom edge. */
    public val bottom: Double,
) {
    /** Whether the boxes overlap; boxes whose edges meet count as overlapping. */
    public fun overlaps(other: Bounds): Boolean = left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom

    /** Whether the point lies inside or on the edge. */
    public fun contains(
        x: Double,
        y: Double,
    ): Boolean = x in left..right && y in top..bottom
}

/**
 * General gameplay facilities for rules: tags, numbers, a seeded random generator and the functions that let rules
 * test touching and tapping. Nothing here is specific to a genre.
 *
 * Touching and tapping use deterministic arithmetic only (no trigonometry), so every target makes the same decisions:
 * bounds are the object's shape (or sprite, or text) box, moved and scaled by the object and its parents. Rotation
 * does not turn the box.
 */
public class Gameplay(
    builder: RuntimeBuilder,
    private val graphics: Graphics2D,
    seed: Long,
) {
    /** Tags. */
    public val tag: ComponentType<Tag> =
        builder.component("gameplay:tag", AuthoringInfo("Tag", "A group name rules can look for", "Gameplay"), TagCodec)

    /** Numbers. */
    public val number: ComponentType<NumberValue> =
        builder.component("gameplay:number", AuthoringInfo("Number", "A score, coins, lives or any count", "Gameplay"), NumberCodec)

    /** Descriptor of [tag]. */
    public val tagDescriptor: ComponentDescriptor<Tag> =
        builder.describe(tag) {
            create { Tag() }
            property(
                "tag",
                ValueKind.Text(maxLength = 100),
                AuthoringInfo("Tag", "For example player, coin or enemy"),
                "",
                { it.name },
                { _, v -> Tag(v) },
            )
        }

    /** Descriptor of [number]. */
    public val numberDescriptor: ComponentDescriptor<NumberValue> =
        builder.describe(number) {
            create { NumberValue() }
            property(
                "number",
                ValueKind.WholeNumber(-NUMBER_LIMIT, NUMBER_LIMIT),
                AuthoringInfo("Value"),
                0L,
                { it.value },
                { n, v -> n.copy(value = v) },
            )
            property(
                "keep",
                ValueKind.YesNo,
                AuthoringInfo(
                    "Keep across scenes",
                    "The number keeps its value in the next scene, on the object with the same tag. Use it for coins, lives or a level.",
                ),
                false,
                { it.keep },
                { n, v -> n.copy(keep = v) },
            )
        }

    /** Each world's random generator, started from the engine seed and checkpointed with the world. */
    public val random: ResourceType<SeededRandom> =
        builder.resource("gameplay:random", AuthoringInfo("Random numbers"), SeededRandom.codec) { SeededRandom(seed) }

    /** World-space bounds of [entity], or `null` when it has no shape, sprite or text. */
    public fun bounds(
        world: World,
        entity: EntityHandle,
    ): Bounds? {
        val (width, height) = size(world, entity) ?: return null
        // Compose translation and scale up the parent chain with + and * only.
        var x = 0.0
        var y = 0.0
        var scaleX = 1.0
        var scaleY = 1.0
        var current: EntityHandle? = entity
        while (current != null) {
            world.get(current, graphics.transform)?.let {
                x = it.x + it.scaleX * x
                y = it.y + it.scaleY * y
                scaleX *= it.scaleX
                scaleY *= it.scaleY
            }
            current = world.parent(current)
        }
        val halfWidth = abs(width * scaleX) / 2
        val halfHeight = abs(height * scaleY) / 2
        return Bounds(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
    }

    private fun size(
        world: World,
        entity: EntityHandle,
    ): Pair<Double, Double>? {
        world.get(entity, graphics.shape)?.let { return it.width to it.height }
        world.get(entity, graphics.sprite)?.let { return it.width to it.height }
        world.get(entity, graphics.text)?.takeIf { it.text.isNotEmpty() }?.let {
            return it.size * Text2D.ESTIMATED_ADVANCE * it.text.length to it.size
        }
        return null
    }

    /** Whether [entity] has tag [name] (ignoring case and surrounding spaces). */
    public fun hasTag(
        world: World,
        entity: EntityHandle,
        name: String,
    ): Boolean =
        world
            .get(entity, tag)
            ?.name
            ?.trim()
            ?.equals(name.trim(), ignoreCase = true) == true

    /** Objects tagged [name], in scene order. */
    public fun tagged(
        world: World,
        name: String,
    ): List<EntityHandle> = world.entities(tag).filter { hasTag(world, it, name) }

    /** How many objects are tagged [name]. */
    public fun countTagged(
        world: World,
        name: String,
    ): Int = tagged(world, name).size

    /** Whether [entity]'s bounds overlap another object tagged [name]. An object never touches itself. */
    public fun touching(
        world: World,
        entity: EntityHandle,
        name: String,
    ): Boolean {
        val own = bounds(world, entity) ?: return false
        return tagged(world, name).any { it != entity && bounds(world, it)?.overlaps(own) == true }
    }

    /**
     * Whether a pointer went down on [entity] this tick: pressed now, not pressed by the same pointer on the previous
     * tick, and inside the object's bounds. Pointer positions are world units.
     */
    public fun tapped(
        world: World,
        entity: EntityHandle,
        input: InputView = world.input,
    ): Boolean {
        val own = bounds(world, entity) ?: return false
        return input.pointers.any { pointer ->
            pointer.pressed &&
                input.previousPointers.none { it.id == pointer.id && it.pressed } &&
                own.contains(pointer.x, pointer.y)
        }
    }

    /**
     * Binds presentation and gameplay properties and functions for rules: transform properties without a prefix,
     * other presentation properties prefixed by component (`shape_width`, `text_text`, …), `tag`, `number`, and the
     * functions `touching`, `tapped`, `firstTagged`, `countTagged`, `textOf`, `random` and `tick`.
     */
    public fun bind(host: ProgramHostBuilder): ProgramHostBuilder =
        host
            .properties(graphics.transformDescriptor)
            .properties(graphics.shapeDescriptor, "shape_")
            .properties(graphics.spriteDescriptor, "sprite_")
            .properties(graphics.textDescriptor, "text_")
            .properties(graphics.cameraDescriptor, "camera_")
            .properties(tagDescriptor)
            .properties(numberDescriptor)
            .function(
                "touching",
                listOf(TextType),
                BoolType,
                info("Is touching", "This object overlaps an object with the tag"),
                cost = 10,
            ) {
                BoolValue(touching(it.world, it.self, text(it.arguments[0])))
            }.function("tapped", emptyList(), BoolType, info("Was tapped", "A finger or click went down on this object"), cost = 5) {
                BoolValue(tapped(it.world, it.self, it.input))
            }.function("firstTagged", listOf(TextType), EntityType, info("Object tagged", "The first object with the tag"), cost = 5) {
                val name = text(it.arguments[0])
                EntityValue(tagged(it.world, name).firstOrNull() ?: throw ProgramHostException("no object is tagged '$name'"))
            }.function("countTagged", listOf(TextType), IntegerType, info("Count tagged", "How many objects have the tag"), cost = 5) {
                IntegerValue(countTagged(it.world, text(it.arguments[0])).toLong())
            }.function("textOf", listOf(IntegerType), TextType, info("Number as text", "For labels such as Score: 3")) {
                TextValue((it.arguments[0] as IntegerValue).value.toString())
            }.function("random", listOf(IntegerType, IntegerType), IntegerType, info("Random number", "A whole number from min to max")) {
                val min = (it.arguments[0] as IntegerValue).value
                val max = (it.arguments[1] as IntegerValue).value
                if (min > max) throw ProgramHostException("the smallest number ($min) is larger than the largest ($max)")
                if (min < -RANDOM_LIMIT || max > RANDOM_LIMIT) {
                    throw ProgramHostException("random numbers must be between -$RANDOM_LIMIT and $RANDOM_LIMIT")
                }
                IntegerValue(min + it.world.resource(random).nextInt((max - min + 1).toInt()))
            }.function("tick", emptyList(), IntegerType, info("Tick", "Ticks since play started; 60 per second")) {
                IntegerValue(it.tick)
            }.effect(RuleBlocks.GO_TO_SCENE, TextType)

    private companion object {
        const val NUMBER_LIMIT = 1_000_000_000_000_000L
        const val RANDOM_LIMIT = 1_000_000_000L

        fun info(
            name: String,
            description: String,
        ) = AuthoringInfo(name, description, "Gameplay")

        fun text(value: Any): String = (value as TextValue).value
    }
}

private object TagCodec : StateCodec<Tag> {
    override fun capture(
        value: Tag,
        context: StateCaptureContext,
    ): StateValue = StateValue.Text(value.name)

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Tag = Tag((state as StateValue.Text).value)
}

private object NumberCodec : StateCodec<NumberValue> {
    override fun capture(
        value: NumberValue,
        context: StateCaptureContext,
    ): StateValue =
        // Numbers that are not kept use the original state, so existing checkpoints and hashes are unchanged.
        if (value.keep) {
            StateValue.Record(mapOf("value" to StateValue.Integer(value.value), "keep" to StateValue.Flag(true)))
        } else {
            StateValue.Integer(value.value)
        }

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): NumberValue =
        when (state) {
            is StateValue.Record -> NumberValue((state.fields.getValue("value") as StateValue.Integer).value, keep = true)
            else -> NumberValue((state as StateValue.Integer).value)
        }
}
