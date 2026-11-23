package dev.spawnkit.ai

/** Real, decodable RGBA PNG files for tests, without any image library: stored deflate blocks, CRC-32 and Adler-32. */
object TestPng {
    /** A [width] × [height] PNG whose pixel at (x, y) is [argb] (0xAARRGGBB). */
    fun make(
        width: Int,
        height: Int,
        argb: (x: Int, y: Int) -> Long = { _, _ -> 0xFFFF0000 },
    ): ByteArray {
        val raw = ArrayList<Byte>()
        for (y in 0 until height) {
            raw += 0
            for (x in 0 until width) {
                val c = argb(x, y)
                raw += (c shr 16).toByte()
                raw += (c shr 8).toByte()
                raw += c.toByte()
                raw += (c shr 24).toByte()
            }
        }
        val pixels = raw.toByteArray()
        val zlib = ArrayList<Byte>()
        zlib += 0x78
        zlib += 0x01
        var at = 0
        do {
            val size = minOf(65_535, pixels.size - at)
            val last = at + size == pixels.size
            zlib += (if (last) 1 else 0).toByte()
            zlib += size.toByte()
            zlib += (size shr 8).toByte()
            zlib += size.inv().toByte()
            zlib += (size.inv() shr 8).toByte()
            for (i in 0 until size) zlib += pixels[at + i]
            at += size
        } while (!last)
        zlib.addAll(int(adler32(pixels)).toList())
        val header = int(width) + int(height) + byteArrayOf(8, 6, 0, 0, 0)
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + chunk("IDAT", zlib.toByteArray()) +
            chunk("IEND", ByteArray(0))
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val body = type.encodeToByteArray() + data
        return int(data.size) + body + int(crc32(body))
    }

    private fun int(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor -0x12477ce0 else crc ushr 1 }
        }
        return crc.inv()
    }

    private fun adler32(bytes: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in bytes) {
            a = (a + (byte.toInt() and 0xFF)) % 65_521
            b = (b + a) % 65_521
        }
        return (b shl 16) or a
    }
}
