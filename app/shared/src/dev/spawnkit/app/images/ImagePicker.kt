package dev.spawnkit.app.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** A picture the creator chose: a name from its file and PNG bytes that fit the project limits (ADR-0012). */
class PickedImage(
    val name: String,
    val png: ByteArray,
)

/** Lets the creator choose a picture on this device. Cancelling gives `null`. */
fun interface ImagePicker {
    fun pick(result: (Result<PickedImage?>) -> Unit)
}

/** Overrides the platform picker, for tests and hosts with their own picker. */
val LocalImagePicker = compositionLocalOf<ImagePicker?> { null }

/** The platform's picture picker, or `null` where importing is not available yet. */
@Composable
expect fun rememberPlatformImagePicker(): ImagePicker?

/** [LocalImagePicker] when provided, else the platform's picker. */
@Composable
fun rememberImagePicker(): ImagePicker? = LocalImagePicker.current ?: rememberPlatformImagePicker()

/** Largest side, in pixels, of pictures that have to be converted or shrunk to fit. */
internal const val FIT_SIDE = 1024

/**
 * A file name without its folder and extension, as an image name. Photo pickers that hide file names give only numbers,
 * which become "Picture".
 */
internal fun imageName(fileName: String): String =
    fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
        .trim()
        .take(80)
        .takeIf { name -> name.any { it.isLetter() } } ?: "Picture"
