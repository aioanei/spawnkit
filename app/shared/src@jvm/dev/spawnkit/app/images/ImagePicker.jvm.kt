package dev.spawnkit.app.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.spawnkit.editing.ProjectImages
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberPlatformImagePicker(): ImagePicker? =
    remember {
        ImagePicker { result ->
            result(
                runCatching {
                    val dialog =
                        JFileChooser().apply {
                            fileFilter = FileNameExtensionFilter("Pictures", "png", "jpg", "jpeg", "gif", "bmp")
                        }
                    if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) readPicture(dialog.selectedFile) else null
                },
            )
        }
    }

/** Reads [file] as a PNG that fits the project limits, converting and shrinking other pictures. */
internal fun readPicture(file: File): PickedImage {
    require(file.length() <= MAX_SOURCE_BYTES) { "This picture is too large to import" }
    return PickedImage(imageName(file.name), fitPng(file.readBytes()))
}

/** [bytes] unchanged when they are a PNG within the limits; otherwise decoded and saved as a PNG that fits. */
internal fun fitPng(bytes: ByteArray): ByteArray {
    if (runCatching { ProjectImages.check(bytes) }.isSuccess) return bytes
    val source = ImageIO.read(bytes.inputStream()) ?: throw IllegalArgumentException("This file is not a picture SpawnKit can read")
    var side = minOf(FIT_SIDE, maxOf(source.width, source.height))
    while (side >= 16) {
        val scale = side.toDouble() / maxOf(source.width, source.height)
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
        if (png.size <= ProjectImages.MAX_IMAGE_BYTES) return png
        side = side * 3 / 4
    }
    throw IllegalArgumentException("This picture cannot be made small enough")
}

private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
