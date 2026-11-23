package dev.spawnkit.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.spawnkit.editing.ImageAsset
import dev.spawnkit.editing.ProjectImages
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Decoded project images (ADR-0012), keyed by content hash, so each picture is decoded once however many canvases,
 * scenes or projects show it. Compose calls it on the UI thread.
 */
object ProjectImageCache {
    private const val MAX_DECODED = 256
    private val decoded = LinkedHashMap<String, ImageBitmap?>()

    /** [images] as the `images` argument of `RuntimeCanvas`: image ID to bitmap. Images that fail to decode are left out. */
    fun bitmaps(images: List<ImageAsset>): Map<String, ImageBitmap> =
        images
            .mapNotNull { image ->
                val bitmap =
                    decoded.getOrPut(image.hash) {
                        runCatching { ProjectImages.decode(image.data).decodeToImageBitmap() }.getOrNull()
                    }
                // Most recently used last, so the oldest pictures are dropped first.
                decoded.remove(image.hash)
                decoded[image.hash] = bitmap
                while (decoded.size > MAX_DECODED) decoded.remove(decoded.keys.first())
                bitmap?.let { image.id to it }
            }.toMap()
}

/** [images] decoded for drawing, remembered while the list is unchanged. */
@Composable
fun rememberProjectImages(images: List<ImageAsset>): Map<String, ImageBitmap> = remember(images) { ProjectImageCache.bitmaps(images) }
