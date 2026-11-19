package dev.spawnkit.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A point or offset in world units (1 unit = 1 tile, y grows downwards). */
@Serializable
public data class Vec2(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Vec2 components must be finite: ($x, $y)" }
    }

    /** Common vectors. */
    public companion object {
        /** The origin. */
        public val Zero: Vec2 = Vec2(0.0, 0.0)
    }
}

/** A width and height in world units. */
@Serializable
public data class Size2(
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && height.isFinite()) { "Size2 components must be finite: ($width, $height)" }
    }
}

/** A size in whole tiles. */
@Serializable
public data class GridSize(
    val width: Int,
    val height: Int,
)

/** Basic shapes used when a [Visual] has no image asset. */
@Serializable
public enum class Shape {
    /** Axis-aligned rectangle. */
    @SerialName("rect")
    Rect,

    /** Rectangle with rounded corners. */
    @SerialName("roundedRect")
    RoundedRect,

    /** Ellipse inscribed in the visual's bounds. */
    @SerialName("circle")
    Circle,
}

/**
 * A color stored as 32-bit `0xAARRGGBB` and serialized as `"#AARRGGBB"` (or read from `"#RRGGBB"`).
 *
 * @property argb alpha, red, green and blue channels packed into the low 32 bits.
 */
@Serializable(with = ColorRgbaSerializer::class)
public data class ColorRgba(
    val argb: Long,
) {
    init {
        require(argb in 0L..0xFFFFFFFFL) { "ARGB value out of range: $argb" }
    }

    /** Formats the color as `#AARRGGBB`. */
    public fun toHex(): String = "#" + argb.toString(HEX_RADIX).uppercase().padStart(ARGB_DIGITS, '0')

    /** Parsing helpers. */
    public companion object {
        private const val HEX_RADIX = 16
        private const val ARGB_DIGITS = 8
        private const val RGB_DIGITS = 6
        private const val OPAQUE = 0xFF000000L

        /** Parses `#AARRGGBB` or `#RRGGBB` (the latter is fully opaque). Returns `null` for malformed input. */
        public fun parseOrNull(text: String): ColorRgba? {
            if (!text.startsWith('#')) return null
            val digits = text.substring(1)
            if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val value = digits.toLongOrNull(HEX_RADIX) ?: return null
            return when (digits.length) {
                ARGB_DIGITS -> ColorRgba(value)
                RGB_DIGITS -> ColorRgba(OPAQUE or value)
                else -> null
            }
        }
    }
}

/** Serializes [ColorRgba] as a hex string. */
public object ColorRgbaSerializer : KSerializer<ColorRgba> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.spawnkit.model.ColorRgba", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ColorRgba,
    ) {
        encoder.encodeString(value.toHex())
    }

    override fun deserialize(decoder: Decoder): ColorRgba {
        val text = decoder.decodeString()
        return ColorRgba.parseOrNull(text) ?: throw SerializationException("Invalid color '$text', expected #AARRGGBB or #RRGGBB")
    }
}
