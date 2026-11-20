package dev.spawnkit.graphics

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ChoiceOption
import dev.spawnkit.runtime.ComponentDescriptor
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCaptureContext
import dev.spawnkit.runtime.StateCodec
import dev.spawnkit.runtime.StateRestoreContext
import dev.spawnkit.runtime.StateValue
import dev.spawnkit.runtime.ValueKind
import dev.spawnkit.runtime.Visibility

/**
 * Local placement of an object relative to its parent (or the world for roots). Units are world units with the y
 * axis pointing down; [rotation] is clockwise in degrees around the object's origin.
 */
public data class Transform2D(
    /** Horizontal position. */
    public val x: Double = 0.0,
    /** Vertical position (down is positive). */
    public val y: Double = 0.0,
    /** Clockwise rotation in degrees. */
    public val rotation: Double = 0.0,
    /** Horizontal scale factor. */
    public val scaleX: Double = 1.0,
    /** Vertical scale factor. */
    public val scaleY: Double = 1.0,
)

/** A filled rectangle or ellipse centred on the object's origin. */
public data class Shape2D(
    /** `rectangle` or `ellipse`. */
    public val form: String = RECTANGLE,
    /** Width in world units. */
    public val width: Double = 1.0,
    /** Height in world units. */
    public val height: Double = 1.0,
    /** Fill color, ARGB. */
    public val color: Long = 0xFF4CAF50,
    /** Draw layer; higher layers draw on top. */
    public val layer: Long = 0,
    /** Whether it is drawn. */
    public val visible: Boolean = true,
) {
    /** Known shape forms. */
    public companion object {
        /** Rectangle form ID. */
        public const val RECTANGLE: String = "rectangle"

        /** Ellipse form ID. */
        public const val ELLIPSE: String = "ellipse"
    }
}

/** An image centred on the object's origin, referenced by project asset ID. */
public data class Sprite2D(
    /** Project asset identifier; empty means "no image chosen". */
    public val asset: String = "",
    /** Width in world units. */
    public val width: Double = 1.0,
    /** Height in world units. */
    public val height: Double = 1.0,
    /** Tint multiplied with the image, ARGB. */
    public val tint: Long = 0xFFFFFFFF,
    /** Draw layer; higher layers draw on top. */
    public val layer: Long = 0,
    /** Whether it is drawn. */
    public val visible: Boolean = true,
)

/** A camera looking at its object's world position. The first active camera in scene order is used. */
public data class Camera2D(
    /** Magnification; 2 shows half as much of the world. */
    public val zoom: Double = 1.0,
    /** Whether this camera may be selected. */
    public val active: Boolean = true,
)

/**
 * Installs the general 2D presentation components through the public runtime SDK, with authoring descriptors so
 * generated inspectors, rules and agents can use them. Presentation never changes simulation state.
 */
public class Graphics2D(
    builder: RuntimeBuilder,
) {
    /** Position, rotation and scale. */
    public val transform: ComponentType<Transform2D> =
        builder.component(
            "graphics:transform",
            AuthoringInfo("Transform", "Where the object is, how it is turned and sized", "Placement"),
            TransformCodec,
        )

    /** Filled shapes. */
    public val shape: ComponentType<Shape2D> =
        builder.component("graphics:shape", AuthoringInfo("Shape", "A colored rectangle or circle", "Appearance"), ShapeCodec)

    /** Images. */
    public val sprite: ComponentType<Sprite2D> =
        builder.component("graphics:sprite", AuthoringInfo("Sprite", "An image from the project", "Appearance"), SpriteCodec)

    /** Cameras. */
    public val camera: ComponentType<Camera2D> =
        builder.component("graphics:camera", AuthoringInfo("Camera", "What part of the scene players see", "View"), CameraCodec)

    /** Descriptor of [transform]. */
    public val transformDescriptor: ComponentDescriptor<Transform2D> =
        builder.describe(transform) {
            create { Transform2D() }
            property("x", ValueKind.Decimal(-1e6, 1e6), AuthoringInfo("X", "Horizontal position"), 0.0, { it.x }, { t, v -> t.copy(x = v) })
            property("y", ValueKind.Decimal(-1e6, 1e6), AuthoringInfo("Y", "Vertical position (down is positive)"), 0.0, {
                it.y
            }, { t, v -> t.copy(y = v) })
            property(
                "rotation",
                ValueKind.Decimal(-36_000.0, 36_000.0, unit = "°"),
                AuthoringInfo("Rotation", "Clockwise turn in degrees"),
                0.0,
                { it.rotation },
                { t, v -> t.copy(rotation = v) },
            )
            property("scaleX", ValueKind.Decimal(-1_000.0, 1_000.0), AuthoringInfo("Width scale"), 1.0, {
                it.scaleX
            }, { t, v -> t.copy(scaleX = v) }, Visibility.Advanced)
            property("scaleY", ValueKind.Decimal(-1_000.0, 1_000.0), AuthoringInfo("Height scale"), 1.0, {
                it.scaleY
            }, { t, v -> t.copy(scaleY = v) }, Visibility.Advanced)
        }

    /** Descriptor of [shape]. */
    public val shapeDescriptor: ComponentDescriptor<Shape2D> =
        builder.describe(shape) {
            create { Shape2D() }
            property(
                "form",
                ValueKind.Choice(listOf(ChoiceOption(Shape2D.RECTANGLE, "Rectangle"), ChoiceOption(Shape2D.ELLIPSE, "Circle / ellipse"))),
                AuthoringInfo("Form"),
                Shape2D.RECTANGLE,
                { it.form },
                { s, v -> s.copy(form = v) },
            )
            property("width", SIZE, AuthoringInfo("Width"), 1.0, { it.width }, { s, v -> s.copy(width = v) })
            property("height", SIZE, AuthoringInfo("Height"), 1.0, { it.height }, { s, v -> s.copy(height = v) })
            property("color", ValueKind.Color, AuthoringInfo("Color"), 0xFF4CAF50, { it.color }, { s, v -> s.copy(color = v) })
            property(
                "layer",
                LAYER,
                AuthoringInfo("Layer", "Higher layers are drawn on top"),
                0L,
                { it.layer },
                { s, v -> s.copy(layer = v) },
            )
            property("visible", ValueKind.YesNo, AuthoringInfo("Visible"), true, { it.visible }, { s, v -> s.copy(visible = v) })
        }

    /** Descriptor of [sprite]. */
    public val spriteDescriptor: ComponentDescriptor<Sprite2D> =
        builder.describe(sprite) {
            create { Sprite2D() }
            property("asset", ValueKind.Text(maxLength = 200), AuthoringInfo("Image", "Image asset from the project"), "", {
                it.asset
            }, { s, v -> s.copy(asset = v) })
            property("width", SIZE, AuthoringInfo("Width"), 1.0, { it.width }, { s, v -> s.copy(width = v) })
            property("height", SIZE, AuthoringInfo("Height"), 1.0, { it.height }, { s, v -> s.copy(height = v) })
            property(
                "tint",
                ValueKind.Color,
                AuthoringInfo("Tint"),
                0xFFFFFFFF,
                { it.tint },
                { s, v -> s.copy(tint = v) },
                Visibility.Advanced,
            )
            property(
                "layer",
                LAYER,
                AuthoringInfo("Layer", "Higher layers are drawn on top"),
                0L,
                { it.layer },
                { s, v -> s.copy(layer = v) },
            )
            property("visible", ValueKind.YesNo, AuthoringInfo("Visible"), true, { it.visible }, { s, v -> s.copy(visible = v) })
        }

    /** Descriptor of [camera]. */
    public val cameraDescriptor: ComponentDescriptor<Camera2D> =
        builder.describe(camera) {
            create { Camera2D() }
            property("zoom", ValueKind.Decimal(0.01, 100.0), AuthoringInfo("Zoom"), 1.0, { it.zoom }, { c, v -> c.copy(zoom = v) })
            property("active", ValueKind.YesNo, AuthoringInfo("Active"), true, { it.active }, { c, v -> c.copy(active = v) })
        }

    private companion object {
        val SIZE = ValueKind.Decimal(0.0, 1e6)
        val LAYER = ValueKind.WholeNumber(-1_000, 1_000)
    }
}

private fun decimals(state: StateValue): List<Double> = (state as StateValue.Sequence).values.map { (it as StateValue.Decimal).value }

private object TransformCodec : StateCodec<Transform2D> {
    override fun capture(
        value: Transform2D,
        context: StateCaptureContext,
    ): StateValue = StateValue.Sequence(listOf(value.x, value.y, value.rotation, value.scaleX, value.scaleY).map(StateValue::Decimal))

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Transform2D {
        val (x, y, rotation, scaleX, scaleY) = decimals(state)
        return Transform2D(x, y, rotation, scaleX, scaleY)
    }
}

private object ShapeCodec : StateCodec<Shape2D> {
    override fun capture(
        value: Shape2D,
        context: StateCaptureContext,
    ): StateValue =
        StateValue.Record(
            mapOf(
                "form" to StateValue.Text(value.form),
                "width" to StateValue.Decimal(value.width),
                "height" to StateValue.Decimal(value.height),
                "color" to StateValue.Integer(value.color),
                "layer" to StateValue.Integer(value.layer),
                "visible" to StateValue.Flag(value.visible),
            ),
        )

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Shape2D {
        val f = (state as StateValue.Record).fields
        return Shape2D(
            text(f, "form"),
            decimal(f, "width"),
            decimal(f, "height"),
            integer(f, "color"),
            integer(f, "layer"),
            flag(f, "visible"),
        )
    }
}

private object SpriteCodec : StateCodec<Sprite2D> {
    override fun capture(
        value: Sprite2D,
        context: StateCaptureContext,
    ): StateValue =
        StateValue.Record(
            mapOf(
                "asset" to StateValue.Text(value.asset),
                "width" to StateValue.Decimal(value.width),
                "height" to StateValue.Decimal(value.height),
                "tint" to StateValue.Integer(value.tint),
                "layer" to StateValue.Integer(value.layer),
                "visible" to StateValue.Flag(value.visible),
            ),
        )

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Sprite2D {
        val f = (state as StateValue.Record).fields
        return Sprite2D(
            text(f, "asset"),
            decimal(f, "width"),
            decimal(f, "height"),
            integer(f, "tint"),
            integer(f, "layer"),
            flag(f, "visible"),
        )
    }
}

private object CameraCodec : StateCodec<Camera2D> {
    override fun capture(
        value: Camera2D,
        context: StateCaptureContext,
    ): StateValue = StateValue.Record(mapOf("zoom" to StateValue.Decimal(value.zoom), "active" to StateValue.Flag(value.active)))

    override fun restore(
        state: StateValue,
        context: StateRestoreContext,
    ): Camera2D {
        val f = (state as StateValue.Record).fields
        return Camera2D(decimal(f, "zoom"), flag(f, "active"))
    }
}

private fun text(
    fields: Map<String, StateValue>,
    key: String,
): String = (fields.getValue(key) as StateValue.Text).value

private fun decimal(
    fields: Map<String, StateValue>,
    key: String,
): Double = (fields.getValue(key) as StateValue.Decimal).value

private fun integer(
    fields: Map<String, StateValue>,
    key: String,
): Long = (fields.getValue(key) as StateValue.Integer).value

private fun flag(
    fields: Map<String, StateValue>,
    key: String,
): Boolean = (fields.getValue(key) as StateValue.Flag).value
