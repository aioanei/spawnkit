package dev.spawnkit.app.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.spawnkit.editing.ProjectImages
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberPlatformImagePicker(): ImagePicker? {
    val context = LocalContext.current.applicationContext
    var waiting by remember { mutableStateOf<((Result<PickedImage?>) -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = waiting
            waiting = null
            callback?.invoke(runCatching { uri?.let { read(context, it) } })
        }
    return remember(context, launcher) {
        ImagePicker { result ->
            waiting = result
            try {
                launcher.launch("image/*")
            } catch (problem: Exception) {
                waiting = null
                result(Result.failure(problem))
            }
        }
    }
}

private fun read(
    context: Context,
    uri: Uri,
): PickedImage {
    val name =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Image"
    val bytes =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val data = input.readNBytes(MAX_SOURCE_BYTES + 1)
            require(data.size <= MAX_SOURCE_BYTES) { "This picture is too large to import" }
            data
        }
    return PickedImage(imageName(name), fitPng(bytes))
}

/** [bytes] unchanged when they are a PNG within the limits; otherwise decoded and saved as a PNG that fits. */
private fun fitPng(bytes: ByteArray): ByteArray {
    if (runCatching { ProjectImages.check(bytes) }.isSuccess) return bytes
    val source =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("This file is not a picture SpawnKit can read")
    var side = minOf(FIT_SIDE, maxOf(source.width, source.height))
    while (side >= 16) {
        val scale = side.toDouble() / maxOf(source.width, source.height)
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (png.size <= ProjectImages.MAX_IMAGE_BYTES) return png
        side = side * 3 / 4
    }
    throw IllegalArgumentException("This picture cannot be made small enough")
}

private const val MAX_SOURCE_BYTES = 40 * 1024 * 1024
