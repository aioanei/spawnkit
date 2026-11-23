package dev.spawnkit.editing

import kotlin.io.encoding.Base64

/**
 * A PNG image kept inside a project (ADR-0012), such as a logo or a character. Sprites name it by [id].
 * [data] is the PNG file in Base64 and [hash] the SHA-256 of its bytes in lower-case hex.
 */
public data class ImageAsset(
    /** Identifier minted like scene and object IDs, for example `img-7`. */
    public val id: String,
    /** Creator-facing name. */
    public val name: String,
    /** SHA-256 of the PNG bytes, lower-case hex. */
    public val hash: String,
    /** The PNG file in Base64. */
    public val data: String,
)

/** Size of a checked PNG. */
public data class ImageInfo(
    /** Width in pixels. */
    public val width: Int,
    /** Height in pixels. */
    public val height: Int,
    /** SHA-256 of the bytes, lower-case hex. */
    public val hash: String,
    /** Length of the PNG file in bytes. */
    public val bytes: Int,
)

/** Checks and limits for images in projects (ADR-0012). */
public object ProjectImages {
    /** Largest PNG file, in bytes. */
    public const val MAX_IMAGE_BYTES: Int = 512 * 1024

    /** Largest total of a project's PNG files, in bytes. */
    public const val MAX_TOTAL_BYTES: Int = 4 * 1024 * 1024

    /** Largest width or height, in pixels. */
    public const val MAX_SIDE: Int = 2048

    /** Value of [dev.spawnkit.runtime.ValueKind.Text.asset] for properties that name a project image. */
    public const val ASSET_KIND: String = "image"

    /** Most images a project holds. */
    public const val MAX_IMAGES: Int = 200

    /** Checks PNG [bytes] and describes them, or throws [IllegalArgumentException] with a plain-language reason. */
    public fun check(bytes: ByteArray): ImageInfo {
        require(bytes.size <= MAX_IMAGE_BYTES) { "Images can be at most ${MAX_IMAGE_BYTES / 1024} KB; this one is ${bytes.size / 1024} KB" }
        require(bytes.size >= 33 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
            "Only PNG images can be used; save the picture as PNG and try again"
        }
        require(bytes.decodeToString(12, 16) == "IHDR") { "This PNG image is damaged" }
        val width = bytes.int(16)
        val height = bytes.int(20)
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) {
            "Images can be at most $MAX_SIDE × $MAX_SIDE pixels; this one is $width × $height"
        }
        return ImageInfo(width, height, Sha256.hex(bytes), bytes.size)
    }

    /** Checks Base64 PNG [data] and describes it, or throws [IllegalArgumentException]. */
    public fun check(data: String): ImageInfo = check(decode(data))

    /** The PNG bytes of Base64 [data], or throws [IllegalArgumentException]. */
    public fun decode(data: String): ByteArray {
        require(data.length <= (MAX_IMAGE_BYTES + 2) / 3 * 4) { "Images can be at most ${MAX_IMAGE_BYTES / 1024} KB" }
        return try {
            Base64.decode(data)
        } catch (problem: IllegalArgumentException) {
            throw IllegalArgumentException("The image data is damaged", problem)
        }
    }

    /** [bytes] in Base64, as projects store them. */
    public fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    /** Bytes of PNG data a project's images take. */
    public fun totalBytes(images: List<ImageAsset>): Long = images.sumOf { it.data.length.toLong() * 3 / 4 }

    private fun ByteArray.int(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
}

/** SHA-256 (FIPS 180-4) in common code, for image content hashes. */
internal object Sha256 {
    fun hex(bytes: ByteArray): String = digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    fun digest(message: ByteArray): ByteArray {
        val h = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)
        val bitLength = message.size.toLong() * 8
        val padded = ((message.size + 9 + 63) / 64) * 64
        val data = message.copyOf(padded)
        data[message.size] = 0x80.toByte()
        for (i in 0 until 8) data[padded - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until padded / 64) {
            for (t in 0 until 16) {
                val o = block * 64 + t * 4
                w[t] = ((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or
                    ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }

    private val K =
        longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).map { it.toInt() }.toIntArray()
}
