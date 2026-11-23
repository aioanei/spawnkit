package dev.spawnkit.hostedai

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A character style the forge can draw (ADR-0013). */
internal enum class CharacterStyle(
    val id: String,
    val label: String,
    /** What the image model is asked for. The photo's person stays recognisable; the background is transparent. */
    val prompt: String,
) {
    Pixel(
        "pixel",
        "Pixel art",
        "Turn the person in this photo into a full-body video game character in retro pixel art style, facing the viewer, " +
            "on a transparent background. Keep their recognisable features, hair and clothing colours. No text.",
    ),
    Cartoon(
        "cartoon",
        "Cartoon",
        "Turn the person in this photo into a full-body cartoon video game character with bold outlines and flat colours, " +
            "facing the viewer, on a transparent background. Keep their recognisable features. No text.",
    ),
    Chibi(
        "chibi",
        "Chibi",
        "Turn the person in this photo into a full-body chibi video game character with a big head and small body, " +
            "facing the viewer, on a transparent background. Keep their recognisable features. No text.",
    ),
    ;

    companion object {
        fun of(id: String): CharacterStyle? = entries.firstOrNull { it.id == id }
    }
}

/** The outcome of one image call: a PNG, or a refusal with the reason to show the creator. */
internal sealed interface ImageResult {
    class Image(
        val png: ByteArray,
    ) : ImageResult

    data class Refused(
        val reason: String,
    ) : ImageResult
}

/** Edits a photo into a character with an image model. The key stays inside the implementation. */
internal interface ImageGateway {
    /** Provider and model, recorded with every image (ADR-0013). */
    val provenance: String

    suspend fun character(
        photo: ByteArray,
        style: CharacterStyle,
    ): ImageResult
}

/**
 * Deterministic post-processing of generated characters (ADR-0013): everything is scaled to fit [MAX_SIDE], and pixel
 * art becomes a crisp grid by a [GRID] × [GRID] box downscale, a fixed 16-colour palette, hard transparency and an
 * 8 × nearest-neighbour upscale. Only integer arithmetic, so the same input always gives the same pixels.
 */
internal object PixelArt {
    const val GRID = 32
    const val SCALE = 8
    const val MAX_SIDE = 512

    /**
     * A 16-colour palette chosen for this project: darks, four skin tones (a live run showed faces turning pink with
     * fewer), and clear hair and clothing colours.
     */
    private val PALETTE =
        intArrayOf(
            0x14161C,
            0x3A3F58,
            0x2E7D32,
            0x8D5524,
            0x5D4037,
            0xBDBDBD,
            0xFFF8E1,
            0xE53935,
            0xFB8C00,
            0xFDD835,
            0x66BB6A,
            0x1E88E5,
            0xF5C7A0,
            0xE0A97E,
            0xC68642,
            0x6B3E26,
        )

    /** [png] as pixel art: [GRID] × [GRID] cells of palette colours or full transparency, [SCALE] × larger. */
    fun pixelate(png: ByteArray): ByteArray {
        val source = read(png)
        val out = BufferedImage(GRID * SCALE, GRID * SCALE, BufferedImage.TYPE_INT_ARGB)
        for (cy in 0 until GRID) {
            for (cx in 0 until GRID) {
                val x0 = cx * source.width / GRID
                val x1 = maxOf(x0 + 1, (cx + 1) * source.width / GRID)
                val y0 = cy * source.height / GRID
                val y1 = maxOf(y0 + 1, (cy + 1) * source.height / GRID)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val c = source.getRGB(x, y)
                        val alpha = (c ushr 24) and 0xFF
                        a += alpha
                        r += ((c shr 16) and 0xFF) * alpha
                        g += ((c shr 8) and 0xFF) * alpha
                        b += (c and 0xFF) * alpha
                    }
                }
                val count = (x1 - x0).toLong() * (y1 - y0)
                // Hard transparency: a cell is drawn only when it is mostly opaque.
                val color = if (a * 2 < count * 255) 0 else (0xFF shl 24) or nearest((r / a).toInt(), (g / a).toInt(), (b / a).toInt())
                for (y in 0 until SCALE) for (x in 0 until SCALE) out.setRGB(cx * SCALE + x, cy * SCALE + y, color)
            }
        }
        return write(out)
    }

    /** [png] scaled down, keeping its shape, so its longest side is at most [MAX_SIDE]. */
    fun fit(png: ByteArray): ByteArray {
        val source = read(png)
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_SIDE) return write(source)
        val width = maxOf(1, source.width * MAX_SIDE / longest)
        val height = maxOf(1, source.height * MAX_SIDE / longest)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        return write(out)
    }

    private fun nearest(
        r: Int,
        g: Int,
        b: Int,
    ): Int =
        PALETTE.minBy { p ->
            val dr = ((p shr 16) and 0xFF) - r
            val dg = ((p shr 8) and 0xFF) - g
            val db = (p and 0xFF) - b
            dr * dr + dg * dg + db * db
        }

    private fun read(png: ByteArray): BufferedImage =
        ImageIO.read(png.inputStream()) ?: throw IllegalArgumentException("The image model sent a picture that cannot be read")

    private fun write(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/**
 * OpenAI image editing (ADR-0013): `POST /v1/images/edits` with the photo, the style's prompt, a transparent background
 * and low quality, over the JDK's HTTP client. The key comes from the server environment and never leaves this class.
 * Moderation refusals become [ImageResult.Refused]; other failures throw, and the caller reports them without details.
 */
internal class OpenAiImageGateway(
    private val apiKey: String,
    private val model: String = DEFAULT_IMAGE_MODEL,
    private val endpoint: String = "https://api.openai.com/v1/images/edits",
) : ImageGateway {
    override val provenance: String = "OpenAI $model"

    private val client: java.net.http.HttpClient =
        java.net.http.HttpClient
            .newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build()

    override suspend fun character(
        photo: ByteArray,
        style: CharacterStyle,
    ): ImageResult {
        val boundary = "spawnkit-" + java.util.UUID.randomUUID()
        val body = ByteArrayOutputStream()

        fun field(
            name: String,
            value: String,
        ) {
            body.write("--$boundary${CRLF}Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF$value$CRLF".encodeToByteArray())
        }
        field("model", model)
        field("prompt", style.prompt)
        field("background", "transparent")
        field("size", "1024x1024")
        field("quality", "low")
        field("output_format", "png")
        body.write(
            "--$boundary${CRLF}Content-Disposition: form-data; name=\"image\"; filename=\"photo.png\"${CRLF}Content-Type: image/png$CRLF$CRLF"
                .encodeToByteArray(),
        )
        body.write(photo)
        body.write("$CRLF--$boundary--$CRLF".encodeToByteArray())
        val request =
            java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(
                    java.net.http.HttpRequest.BodyPublishers
                        .ofByteArray(body.toByteArray()),
                ).build()
        val response =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers
                        .ofString(),
                )
            }
        val json =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(response.body()) as? kotlinx.serialization.json.JsonObject
            }.getOrNull()
        if (response.statusCode() == 400) {
            val code =
                (
                    (
                        json?.get(
                            "error",
                        ) as? kotlinx.serialization.json.JsonObject
                    )?.get("code") as? kotlinx.serialization.json.JsonPrimitive
                )?.content
            if (code == "moderation_blocked" || code == "content_policy_violation") {
                return ImageResult.Refused("The image model declined this photo. Try a different photo.")
            }
        }
        check(response.statusCode() == 200) { "Image model answered ${response.statusCode()}" }
        val data = (json?.get("data") as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject
        val encoded = (data?.get("b64_json") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("No image in the answer")
        return ImageResult.Image(
            java.util.Base64
                .getDecoder()
                .decode(encoded),
        )
    }
}

private const val CRLF = "\r\n"

/** The image model Character Forge uses unless `SPAWNKIT_IMAGE_MODEL` names another; `gpt-image-1` was retired. */
internal const val DEFAULT_IMAGE_MODEL = "gpt-image-1-mini"
