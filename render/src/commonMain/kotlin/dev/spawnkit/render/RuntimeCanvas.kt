package dev.spawnkit.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.spawnkit.graphics.Affine2D
import dev.spawnkit.graphics.CameraView
import dev.spawnkit.graphics.DrawItem
import dev.spawnkit.graphics.RenderList
import dev.spawnkit.graphics.Shape2D
import dev.spawnkit.graphics.ViewTransform
import dev.spawnkit.runtime.EntityHandle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Visual options for drawing a [RenderList]; editors enable the grid and selection outlines, players do not. */
data class CanvasStyle(
    /** Clear color behind the scene. */
    val background: Color = Color(0xFF1B1D23),
    /** Whether to draw a one-unit grid. */
    val showGrid: Boolean = false,
    /** Grid line color. */
    val gridColor: Color = Color(0x22FFFFFF),
    /** Selection outline color. */
    val selectionColor: Color = Color(0xFF4FC3F7),
)

/**
 * Draws a general-runtime frame. The same composable renders the editor viewport and exported players, so what
 * creators see while editing is what players see. [camera] defaults to the scene's active camera.
 */
@Composable
fun RuntimeCanvas(
    frame: RenderList,
    modifier: Modifier = Modifier,
    camera: CameraView = frame.camera ?: CameraView(),
    pixelsPerUnit: Float = 48f,
    images: Map<String, ImageBitmap> = emptyMap(),
    selection: Set<EntityHandle> = emptySet(),
    style: CanvasStyle = CanvasStyle(),
) {
    Canvas(modifier) {
        drawRenderList(
            frame,
            ViewTransform(camera, size.width.toDouble(), size.height.toDouble(), pixelsPerUnit.toDouble()),
            images,
            selection,
            style,
        )
    }
}

/** Draws [frame] through [view] into this scope; usable from custom canvases and offscreen rendering. */
fun DrawScope.drawRenderList(
    frame: RenderList,
    view: ViewTransform,
    images: Map<String, ImageBitmap> = emptyMap(),
    selection: Set<EntityHandle> = emptySet(),
    style: CanvasStyle = CanvasStyle(),
) {
    drawRect(style.background)
    if (style.showGrid) drawGrid(view, style.gridColor)
    val toScreen = view.worldToScreen
    for (item in frame.items) {
        val screen = toScreen * item.transform
        withTransform({ transform(screen.toMatrix()) }) {
            val topLeft = Offset((-item.width / 2).toFloat(), (-item.height / 2).toFloat())
            val box = Size(item.width.toFloat(), item.height.toFloat())
            when (item) {
                is DrawItem.Shape -> {
                    val color = argb(item.color)
                    if (item.form == Shape2D.ELLIPSE) drawOval(color, topLeft, box) else drawRect(color, topLeft, box)
                }

                is DrawItem.Sprite -> {
                    drawSprite(item, images[item.asset], topLeft, box)
                }
            }
            if (item.entity in selection) {
                val pixelScale = sqrt(abs(screen.a * screen.d - screen.b * screen.c)).coerceAtLeast(1e-6)
                drawRect(style.selectionColor, topLeft, box, style = Stroke(width = (2.0 / pixelScale).toFloat()))
            }
        }
    }
}

private fun DrawScope.drawSprite(
    item: DrawItem.Sprite,
    image: ImageBitmap?,
    topLeft: Offset,
    box: Size,
) {
    if (image == null || image.width == 0 || image.height == 0) {
        // Missing or not-yet-chosen image: a visible placeholder rather than nothing.
        drawRect(argb(item.tint).copy(alpha = 0.35f), topLeft, box)
        drawLine(Color.White.copy(alpha = 0.6f), topLeft, topLeft + Offset(box.width, box.height), strokeWidth = box.minDimension / 20)
        return
    }
    val toImage =
        Affine2D(1.0, 0.0, 0.0, 1.0, topLeft.x.toDouble(), topLeft.y.toDouble()) *
            Affine2D(item.width / image.width, 0.0, 0.0, item.height / image.height)
    withTransform({ transform(toImage.toMatrix()) }) {
        val filter = if (item.tint == 0xFFFFFFFFL) null else ColorFilter.tint(argb(item.tint), BlendMode.Modulate)
        drawImage(
            image,
            IntOffset.Zero,
            IntSize(image.width, image.height),
            IntOffset.Zero,
            IntSize(image.width, image.height),
            colorFilter = filter,
        )
    }
}

private fun DrawScope.drawGrid(
    view: ViewTransform,
    color: Color,
) {
    val (left, top) = view.toWorld(0.0, 0.0)
    val (right, bottom) = view.toWorld(size.width.toDouble(), size.height.toDouble())
    val step = if (view.scale < 8) 10.0 else 1.0
    var x = floor(left / step) * step
    while (x <= right) {
        val sx =
            view.worldToScreen
                .apply(x, 0.0)
                .first
                .toFloat()
        drawLine(color, Offset(sx, 0f), Offset(sx, size.height))
        x += step
    }
    var y = floor(top / step) * step
    while (y <= bottom) {
        val sy =
            view.worldToScreen
                .apply(0.0, y)
                .second
                .toFloat()
        drawLine(color, Offset(0f, sy), Offset(size.width, sy))
        y += step
    }
}

/** Converts an ARGB long (`0xAARRGGBB`) to a Compose color. */
fun argb(value: Long): Color = Color(value.toInt())

/** Compose's 4×4 column-major matrix for this 2D affine transform. */
fun Affine2D.toMatrix(): Matrix =
    Matrix().apply {
        values[Matrix.ScaleX] = a.toFloat()
        values[Matrix.SkewY] = b.toFloat()
        values[Matrix.SkewX] = c.toFloat()
        values[Matrix.ScaleY] = d.toFloat()
        values[Matrix.TranslateX] = tx.toFloat()
        values[Matrix.TranslateY] = ty.toFloat()
    }
