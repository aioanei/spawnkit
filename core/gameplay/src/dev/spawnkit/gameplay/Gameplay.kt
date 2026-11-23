package dev.spawnkit.gameplay

import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.graphics.Text2D
import dev.spawnkit.program.BoolType
import dev.spawnkit.program.BoolValue
import dev.spawnkit.program.DecimalType
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.EntityType
import dev.spawnkit.program.EntityValue
import dev.spawnkit.program.HostCallContext
import dev.spawnkit.program.IntegerType
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.ProgramHostBuilder
import dev.spawnkit.program.ProgramHostException
import dev.spawnkit.program.TextType
import dev.spawnkit.program.TextValue
import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.CheckpointSystem
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
import dev.spawnkit.runtime.WorldCommands
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

/** Marks an object as a template: it stays in the builder, but only copies made by "Spawn a copy" appear in play. */
public data class Template(
    /** Whether the object is a template. */
    public val enabled: Boolean = true,
)

/** Steady movement in world units per second, which the engine applies every tick; spawned copies use it. */
public data class Motion(
    /** Units per second along x (positive is right). */
    public val vx: Double = 0.0,
    /** Units per second along y (positive is down). */
    public val vy: Double = 0.0,
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

    /** Templates for spawning. */
    public val template: ComponentType<Template> =
        builder.component(
            "gameplay:template",
            AuthoringInfo("Template", "Only copies of this object appear in play, made by Spawn a copy", "Gameplay"),
            TemplateCodec,
        )

    /** Descriptor of [template]. */
    public val templateDescriptor: ComponentDescriptor<Template> =
        builder.describe(template) {
            create { Template() }
            property(
                "template",
                ValueKind.YesNo,
                AuthoringInfo("Template", "When on, the object is left out of play; Spawn a copy blocks make copies of it."),
                true,
                { it.enabled },
                { t, v -> t.copy(enabled = v) },
            )
        }

    /** Steady movement. */
    public val motion: ComponentType<Motion> =
        builder.component("gameplay:motion", AuthoringInfo("Steady movement", "Moves the object every moment", "Gameplay"), MotionCodec)

    /** Descriptor of [motion]. */
    public val motionDescriptor: ComponentDescriptor<Motion> =
        builder.describe(motion) {
            create { Motion() }
            property(
                "vx",
                ValueKind.Decimal(-MAX_SPEED, MAX_SPEED),
                AuthoringInfo("Speed across", "Units per second; negative moves left."),
                0.0,
                { it.vx },
                { m, v -> m.copy(vx = v) },
            )
            property(
                "vy",
                ValueKind.Decimal(-MAX_SPEED, MAX_SPEED),
                AuthoringInfo("Speed down", "Units per second; negative moves up."),
                0.0,
                { it.vy },
                { m, v -> m.copy(vy = v) },
            )
        }

    /** Physics bodies. */
    public val body: ComponentType<Body> =
        builder.component(
            "gameplay:body",
            AuthoringInfo("Body", "Falls with gravity, jumps and is stopped by solid objects", "Physics"),
            BodyCodec,
        )

    /** Descriptor of [body]. */
    public val bodyDescriptor: ComponentDescriptor<Body> =
        builder.describe(body) {
            create { Body() }
            property(
                "vx",
                ValueKind.Decimal(-MAX_SPEED, MAX_SPEED),
                AuthoringInfo("Speed across", "Units per second; negative moves left. Solids stop it."),
                0.0,
                { it.vx },
                { b, v -> b.copy(vx = v) },
            )
            property(
                "vy",
                ValueKind.Decimal(-MAX_SPEED, MAX_SPEED),
                AuthoringInfo("Speed down", "Units per second; negative moves up. Gravity changes it every moment."),
                0.0,
                { it.vy },
                { b, v -> b.copy(vy = v) },
            )
            property(
                "gravity",
                ValueKind.Decimal(-MAX_SPEED, MAX_SPEED),
                AuthoringInfo("Gravity", "How strongly the body is pulled down. 0 floats, as in a game seen from above."),
                Body.DEFAULT_GRAVITY,
                { it.gravity },
                { b, v -> b.copy(gravity = v) },
            )
            property(
                "bounce",
                ValueKind.Decimal(0.0, 1.0),
                AuthoringInfo("Bounce", "0 stops when it hits something; 1 bounces back as fast as it came."),
                0.0,
                { it.bounce },
                { b, v -> b.copy(bounce = v) },
            )
            property(
                "maxFall",
                ValueKind.Decimal(0.0, MAX_SPEED),
                AuthoringInfo("Fastest fall", "Gravity never makes it fall faster than this, in units per second."),
                Body.DEFAULT_MAX_FALL,
                { it.maxFall },
                { b, v -> b.copy(maxFall = v) },
            )
        }

    /** Solid objects. */
    public val solid: ComponentType<Solid> =
        builder.component(
            "gameplay:solid",
            AuthoringInfo("Solid", "Bodies cannot pass through it: ground, walls and platforms", "Physics"),
            SolidCodec,
        )

    /** Descriptor of [solid]. */
    public val solidDescriptor: ComponentDescriptor<Solid> =
        builder.describe(solid) {
            create { Solid() }
            property(
                "solid",
                ValueKind.YesNo,
                AuthoringInfo("Solid", "When on, bodies stand on it and are stopped by it. Bodies on it move with it."),
                true,
                { it.enabled },
                { s, v -> s.copy(enabled = v) },
            )
        }

    /** Following another object. */
    public val follow: ComponentType<Follow> =
        builder.component(
            "gameplay:follow",
            AuthoringInfo("Follow", "Stays with an object that has a tag; put it on a camera to follow the player", "View"),
            FollowCodec,
        )

    /** Descriptor of [follow]. */
    public val followDescriptor: ComponentDescriptor<Follow> =
        builder.describe(follow) {
            create { Follow() }
            property(
                "tag",
                ValueKind.Text(maxLength = 100),
                AuthoringInfo("Follow tag", "The first object with this tag is followed, for example player."),
                "player",
                { it.tag },
                { f, v -> f.copy(tag = v) },
            )
            property(
                "across",
                ValueKind.YesNo,
                AuthoringInfo("Follow across", "Moves left and right with the object."),
                true,
                { it.across },
                { f, v -> f.copy(across = v) },
            )
            property(
                "down",
                ValueKind.YesNo,
                AuthoringInfo("Follow up and down", "Moves up and down with the object. Turn it off for a level that only scrolls sideways."),
                true,
                { it.down },
                { f, v -> f.copy(down = v) },
            )
        }

    init {
        // After rules, so a rule that changes the speed takes effect in the same moment.
        builder.system("gameplay:motion", after = setOf("program:execute")) { MotionSystem() }
        // After everything that moves objects, so bodies ride platforms and stop at walls wherever they were moved.
        builder.system("gameplay:physics", after = setOf("program:execute", "gameplay:motion")) { PhysicsSystem(this, graphics) }
        builder.system("gameplay:follow", after = setOf("gameplay:physics")) { FollowSystem(this, graphics) }
    }

    /** Moves objects with [motion] every tick. It keeps no state of its own, so checkpoints capture nothing. */
    private inner class MotionSystem : CheckpointSystem {
        override fun update(
            world: World,
            commands: WorldCommands,
        ) {
            for (entity in world.entities(motion, graphics.transform)) {
                val move = world.get(entity, motion) ?: continue
                if (move.vx == 0.0 && move.vy == 0.0) continue
                val at = world.get(entity, graphics.transform) ?: continue
                world.set(entity, graphics.transform, at.copy(x = at.x + move.vx / TICKS, y = at.y + move.vy / TICKS))
            }
        }

        override fun captureState(context: StateCaptureContext): StateValue = StateValue.Null

        override fun restoreState(
            state: StateValue,
            context: StateRestoreContext,
        ) = Unit
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

    /**
     * Whether [entity] is wholly outside a [width] × [height] box centred on the first active camera (or the origin),
     * divided by the camera's zoom. Objects without bounds never leave.
     */
    public fun leftArea(
        world: World,
        entity: EntityHandle,
        width: Double,
        height: Double,
    ): Boolean {
        val own = bounds(world, entity) ?: return false
        var centerX = 0.0
        var centerY = 0.0
        var zoom = 1.0
        val camera = world.entities(graphics.camera).firstOrNull { world.get(it, graphics.camera)?.active == true }
        if (camera != null) {
            var current: EntityHandle? = camera
            while (current != null) {
                world.get(current, graphics.transform)?.let {
                    centerX = it.x + it.scaleX * centerX
                    centerY = it.y + it.scaleY * centerY
                }
                current = world.parent(current)
            }
            zoom = world.get(camera, graphics.camera)?.zoom?.takeIf { it > 0.0 } ?: 1.0
        }
        val halfWidth = width / 2 / zoom
        val halfHeight = height / 2 / zoom
        return own.right < centerX - halfWidth || own.left > centerX + halfWidth || own.bottom < centerY - halfHeight ||
            own.top > centerY + halfHeight
    }

    // Include writes staged earlier in the same rule; reading world components here would see the old position.
    private fun position(
        context: HostCallContext,
        entity: EntityHandle,
    ): Pair<Double, Double> {
        var x = 0.0
        var y = 0.0
        var current: EntityHandle? = entity
        while (current != null) {
            val atX = (context.read(current, "x") as? DecimalValue)?.value ?: 0.0
            val atY = (context.read(current, "y") as? DecimalValue)?.value ?: 0.0
            val scaleX = (context.read(current, "scaleX") as? DecimalValue)?.value ?: 1.0
            val scaleY = (context.read(current, "scaleY") as? DecimalValue)?.value ?: 1.0
            x = atX + scaleX * x
            y = atY + scaleY * y
            current = context.world.parent(current)
        }
        return x to y
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
     * other presentation properties prefixed by component (`shape_width`, `text_text`, …), `tag`, `number`, steady
     * movement, body and follow properties (`motion_vx`, `body_vy`, `follow_tag`, …), and the functions `touching`,
     * `tapped`, `onGround`, `firstTagged`, `countTagged`, `textOf`, `random` and `tick`.
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
            .properties(motionDescriptor, "motion_")
            .properties(bodyDescriptor, "body_")
            .properties(followDescriptor, "follow_")
            .function(
                "onGround",
                emptyList(),
                BoolType,
                info("Is on the ground", "This object's body stood on a solid object after the last moment"),
            ) {
                BoolValue(it.world.get(it.self, body)?.onGround == true)
            }.function(
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
            }.function(
                "leftArea",
                listOf(DecimalType, DecimalType),
                BoolType,
                info("Left the play area", "This object is wholly outside a box of this size around the camera"),
                cost = 5,
            ) {
                BoolValue(leftArea(it.world, it.self, (it.arguments[0] as DecimalValue).value, (it.arguments[1] as DecimalValue).value))
            }.function("decimalText", listOf(DecimalType), TextType, info("Decimal as text", "A decimal number written out")) {
                TextValue((it.arguments[0] as DecimalValue).value.toString())
            }.function(
                "worldX",
                listOf(EntityType),
                DecimalType,
                info("World x", "Horizontal position with parent translation and scale"),
            ) {
                DecimalValue(position(it, (it.arguments[0] as EntityValue).entity).first)
            }.function("worldY", listOf(EntityType), DecimalType, info("World y", "Vertical position with parent translation and scale")) {
                DecimalValue(position(it, (it.arguments[0] as EntityValue).entity).second)
            }.effect(RuleBlocks.GO_TO_SCENE, TextType)
            .effect(RuleBlocks.SPAWN, TextType)

    private companion object {
        const val TICKS = 60.0
        const val MAX_SPEED = 1_000.0
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

private object TemplateCodec : StateCodec<Template> {
    override fun capture(
        value: Template,
        context: StateCaptureContext,
    ): StateValue = StateValue.Flag(value.enabled)

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Template = Template((state as StateValue.Flag).value)
}

private object MotionCodec : StateCodec<Motion> {
    override fun capture(
        value: Motion,
        context: StateCaptureContext,
    ): StateValue = StateValue.Record(mapOf("vx" to StateValue.Decimal(value.vx), "vy" to StateValue.Decimal(value.vy)))

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Motion {
        val fields = (state as StateValue.Record).fields
        return Motion((fields.getValue("vx") as StateValue.Decimal).value, (fields.getValue("vy") as StateValue.Decimal).value)
    }
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
