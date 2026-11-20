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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
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
    /** Selection outline color; white contrasts with every swatch color. */
    val selectionColor: Color = Color.White,
    /** Selection outline width in pixels; pass a density-scaled value on high-density screens. */
    val selectionWidthPx: Float = 2f,
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
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        drawRenderList(
            frame,
            ViewTransform(safeCamera(camera), size.width.toDouble(), size.height.toDouble(), safeScale(pixelsPerUnit)),
            images,
            selection,
            style,
            textMeasurer,
        )
    }
}

/**
 * Draws [frame] through [view] into this scope; usable from custom canvases and offscreen rendering. Text items are
 * drawn only when a [textMeasurer] is supplied (from `rememberTextMeasurer()`); [RuntimeCanvas] always supplies one.
 */
fun DrawScope.drawRenderList(
    frame: RenderList,
    view: ViewTransform,
    images: Map<String, ImageBitmap> = emptyMap(),
    selection: Set<EntityHandle> = emptySet(),
    style: CanvasStyle = CanvasStyle(),
    textMeasurer: TextMeasurer? = null,
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

                is DrawItem.Text -> {
                    if (textMeasurer != null) drawLabel(item, textMeasurer)
                }
            }
            if (item.entity in selection) {
                val pixelScale = sqrt(abs(screen.a * screen.d - screen.b * screen.c)).coerceAtLeast(1e-6)
                drawRect(style.selectionColor, topLeft, box, style = Stroke(width = (style.selectionWidthPx / pixelScale).toFloat()))
            }
        }
    }
}

/** Font size text is laid out at before scaling into world units; large enough for crisp glyph outlines. */
private val TEXT_REFERENCE_SIZE = 64.sp

/** Draws [item] centred on the origin, scaled so one line is exactly the item's height in world units. */
private fun DrawScope.drawLabel(
    item: DrawItem.Text,
    measurer: TextMeasurer,
) {
    val layout =
        measurer.measure(
            item.text,
            TextStyle(color = argb(item.color), fontSize = TEXT_REFERENCE_SIZE),
            maxLines = 1,
            softWrap = false,
        )
    val lineHeight = layout.size.height.toFloat()
    if (lineHeight <= 0f) return
    val scale = (item.height / lineHeight).toFloat()
    val toText = Affine2D(scale.toDouble(), 0.0, 0.0, scale.toDouble(), -layout.size.width * scale / 2.0, -item.height / 2.0)
    withTransform({ transform(toText.toMatrix()) }) { drawText(layout) }
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
    // Power-of-ten spacing keeps lines at least MIN_GRID_PX apart at any zoom; integer indices and a cap bound the work.
    var step = 1.0
    while (step * view.scale < MIN_GRID_PX) step *= 10.0
    while (step / 10.0 * view.scale >= MIN_GRID_PX && step > 1e-6) step /= 10.0
    val (left, top) = view.toWorld(0.0, 0.0)
    val (right, bottom) = view.toWorld(view.widthPx, view.heightPx)
    val firstX = floor(left / step).toLong()
    val firstY = floor(top / step).toLong()
    val countX = ((right - left) / step).toLong().coerceIn(0, MAX_GRID_LINES) + 2
    val countY = ((bottom - top) / step).toLong().coerceIn(0, MAX_GRID_LINES) + 2
    for (i in 0 until countX) {
        val sx =
            view.worldToScreen
                .apply((firstX + i) * step, 0.0)
                .first
                .toFloat()
        drawLine(color, Offset(sx, 0f), Offset(sx, view.heightPx.toFloat()))
    }
    for (i in 0 until countY) {
        val sy =
            view.worldToScreen
                .apply(0.0, (firstY + i) * step)
                .second
                .toFloat()
        drawLine(color, Offset(0f, sy), Offset(view.widthPx.toFloat(), sy))
    }
}

private const val MIN_GRID_PX = 32.0
private const val MAX_GRID_LINES = 1_000L

/** A camera safe to render: an invalid zoom (non-finite or not positive) falls back to 1. */
private fun safeCamera(camera: CameraView): CameraView =
    if (camera.zoom.isFinite() &&
        camera.zoom > 0.0
    ) {
        camera
    } else {
        camera.copy(zoom = 1.0)
    }

private fun safeScale(pixelsPerUnit: Float): Double = if (pixelsPerUnit.isFinite() && pixelsPerUnit > 0f) pixelsPerUnit.toDouble() else 48.0

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
