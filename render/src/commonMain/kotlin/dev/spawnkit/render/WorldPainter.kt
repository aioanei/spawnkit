package dev.spawnkit.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import dev.spawnkit.engine.RuntimeWorld
import dev.spawnkit.model.ColorRgba
import dev.spawnkit.model.EmptyTileChars
import dev.spawnkit.model.Scene
import dev.spawnkit.model.Shape
import dev.spawnkit.model.TileBehavior
import dev.spawnkit.model.TileDef
import dev.spawnkit.model.Visual
import dev.spawnkit.model.charAt
import dev.spawnkit.model.prefab
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

internal val LetterboxColor = Color(0xFF0B0D12)

/**
 * Draws a [RuntimeWorld] with Compose primitives. Presentation only: it reads the world and never mutates it.
 * Decorations (eyes, shine, flags) are chosen from entity tags (`player`, `enemy`, `pickup`, `goal`, `checkpoint`).
 */
internal class WorldPainter {
    private var cachedScene: Scene? = null
    private var tileDefs: Array<TileDef?> = emptyArray()
    private var tileColors: Array<Color> = emptyArray()
    private var cachedWorld: RuntimeWorld? = null
    private var entityTags: Array<Set<String>> = emptyArray()
    private var playerFacing = 1f
    private val path = Path()

    fun draw(
        scope: DrawScope,
        world: RuntimeWorld,
        positions: RenderPositions,
        alpha: Double,
    ): Unit =
        with(scope) {
            prepare(world)
            val settings = world.project.settings
            val view = settings.viewportTiles
            val t = computeViewport(size.width, size.height, view.width.toFloat(), view.height.toFloat())
            if (t.scale <= 0f) return
            val p = world.playerIndex
            val camera =
                cameraOrigin(
                    targetX = positions.x(world, p, alpha) + world.bodyWidth(p) / 2,
                    targetY = positions.y(world, p, alpha) + world.bodyHeight(p) / 2,
                    viewW = settings.viewportTiles.width,
                    viewH = settings.viewportTiles.height,
                    sceneW =
                        world.scene.size.width
                            .toDouble(),
                    sceneH =
                        world.scene.size.height
                            .toDouble(),
                )
            drawRect(LetterboxColor)
            clipRect(t.offsetX, t.offsetY, t.offsetX + t.widthPx, t.offsetY + t.heightPx) {
                val background = world.scene.background.toColor()
                drawRect(
                    brush = Brush.verticalGradient(listOf(lerp(background, Color.White, 0.12f), lerp(background, Color.Black, 0.18f))),
                    topLeft = Offset(t.offsetX, t.offsetY),
                    size = Size(t.widthPx, t.heightPx),
                )
                val frame = Frame(t, camera)
                drawTiles(world, frame)
                drawEntities(world, frame, positions, alpha)
            }
        }

    private fun prepare(world: RuntimeWorld) {
        if (cachedScene !== world.scene) {
            val scene = world.scene
            val width = scene.size.width
            tileDefs =
                Array(width * scene.size.height) { index ->
                    val x = index % width
                    val y = index / width
                    scene.layers.asReversed().firstNotNullOfOrNull { layer ->
                        layer.charAt(x, y).takeUnless { it in EmptyTileChars }?.let { world.project.tileset[it] }
                    }
                }
            tileColors = Array(tileDefs.size) { tileDefs[it]?.color?.toColor() ?: Color.Transparent }
            cachedScene = scene
        }
        if (cachedWorld !== world) {
            val instances = world.scene.entities.associateBy { it.id }
            entityTags =
                Array(world.entityCount) { i ->
                    val instance = instances[world.entityId(i)]
                    val prefabTags = instance?.prefab?.let { world.project.prefab(it)?.tags }.orEmpty()
                    prefabTags + instance?.tags.orEmpty()
                }
            cachedWorld = world
        }
    }

    private class Frame(
        val t: ViewportTransform,
        val camera: CameraOrigin,
    ) {
        fun sx(worldX: Double): Float = t.offsetX + ((worldX - camera.x) * t.scale).toFloat()

        fun sy(worldY: Double): Float = t.offsetY + ((worldY - camera.y) * t.scale).toFloat()
    }

    private fun DrawScope.drawTiles(
        world: RuntimeWorld,
        f: Frame,
    ) {
        val scene = world.scene
        val settings = world.project.settings
        val s = f.t.scale
        val firstColumn = floor(f.camera.x).toInt().coerceAtLeast(0)
        val lastColumn = ceil(f.camera.x + settings.viewportTiles.width).toInt().coerceAtMost(scene.size.width - 1)
        val firstRow = floor(f.camera.y).toInt().coerceAtLeast(0)
        val lastRow = ceil(f.camera.y + settings.viewportTiles.height).toInt().coerceAtMost(scene.size.height - 1)
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val index = row * scene.size.width + column
                val def = tileDefs[index] ?: continue
                val color = tileColors[index]
                val left = f.sx(column.toDouble())
                val top = f.sy(row.toDouble())
                when (def.behavior) {
                    TileBehavior.Solid -> {
                        drawSolid(
                            color,
                            left,
                            top,
                            s,
                            exposedTop =
                                row == 0 || tileDefs[index - scene.size.width]?.behavior != TileBehavior.Solid,
                        )
                    }

                    TileBehavior.OneWay -> {
                        drawPlank(color, left, top, s)
                    }

                    TileBehavior.Hazard -> {
                        drawSpikes(color, left, top, s)
                    }

                    TileBehavior.Decor -> {
                        drawBush(color, left, top, s)
                    }

                    TileBehavior.Empty -> {}
                }
            }
        }
    }

    private fun DrawScope.drawSolid(
        color: Color,
        left: Float,
        top: Float,
        s: Float,
        exposedTop: Boolean,
    ) {
        drawRect(color, Offset(left, top), Size(s + 0.5f, s + 0.5f))
        drawRect(lerp(color, Color.Black, 0.18f), Offset(left, top + s * 0.82f), Size(s + 0.5f, s * 0.18f + 0.5f))
        if (exposedTop) drawRect(lerp(color, Color.White, 0.28f), Offset(left, top), Size(s + 0.5f, s * 0.16f))
    }

    private fun DrawScope.drawPlank(
        color: Color,
        left: Float,
        top: Float,
        s: Float,
    ) {
        drawRoundRect(color, Offset(left + s * 0.02f, top), Size(s * 0.96f, s * 0.32f), CornerRadius(s * 0.08f))
        drawRect(lerp(color, Color.Black, 0.25f), Offset(left + s * 0.02f, top + s * 0.26f), Size(s * 0.96f, s * 0.06f))
    }

    private fun DrawScope.drawSpikes(
        color: Color,
        left: Float,
        top: Float,
        s: Float,
    ) {
        path.reset()
        val spikes = 3
        val w = s / spikes
        for (k in 0 until spikes) {
            path.moveTo(left + k * w, top + s)
            path.lineTo(left + k * w + w / 2, top + s * 0.35f)
            path.lineTo(left + (k + 1) * w, top + s)
            path.close()
        }
        drawPath(path, color)
    }

    private fun DrawScope.drawBush(
        color: Color,
        left: Float,
        top: Float,
        s: Float,
    ) {
        drawCircle(color, s * 0.32f, Offset(left + s * 0.3f, top + s * 0.72f))
        drawCircle(lerp(color, Color.White, 0.1f), s * 0.38f, Offset(left + s * 0.62f, top + s * 0.66f))
    }

    private fun DrawScope.drawEntities(
        world: RuntimeWorld,
        f: Frame,
        positions: RenderPositions,
        alpha: Double,
    ) {
        val s = f.t.scale
        val p = world.playerIndex
        if (world.vx(p) > 0.0) {
            playerFacing = 1f
        } else if (world.vx(p) < 0.0) {
            playerFacing = -1f
        }
        for (i in 0 until world.entityCount) {
            if (!world.isAlive(i)) continue
            val visual = world.visual(i) ?: continue
            val tags = entityTags.getOrElse(i) { emptySet() }
            val bob = if ("pickup" in tags) (sin(world.tick / 9.0 + i) * 0.06).toFloat() * s else 0f
            val left = f.sx(positions.x(world, i, alpha))
            val top = f.sy(positions.y(world, i, alpha)) + bob
            val w = (visual.size.width * s).toFloat()
            val h = (visual.size.height * s).toFloat()
            val color = visual.color.toColor().let { if (i == p && world.playerInGracePeriod) it.copy(alpha = 0.55f) else it }
            when {
                "goal" in tags -> drawFlag(color, left, top, w, h, big = true)
                "checkpoint" in tags -> drawFlag(color, left, top, w, h, big = false)
                else -> drawShape(visual, color, left, top, w, h)
            }
            when {
                i == p -> drawEyes(left, top, w, h, playerFacing)
                "enemy" in tags -> drawEyes(left, top, w, h, world.direction(i).toFloat())
                "pickup" in tags -> drawCircle(Color.White.copy(alpha = 0.7f), w * 0.14f, Offset(left + w * 0.34f, top + h * 0.32f))
            }
        }
    }

    private fun DrawScope.drawShape(
        visual: Visual,
        color: Color,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
    ) {
        when (visual.shape) {
            Shape.Rect -> drawRect(color, Offset(left, top), Size(w, h))
            Shape.RoundedRect -> drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(minOf(w, h) * 0.3f))
            Shape.Circle -> drawOval(color, Offset(left, top), Size(w, h))
        }
    }

    private fun DrawScope.drawEyes(
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        facing: Float,
    ) {
        val eyeRadius = minOf(w, h) * 0.13f
        val eyeY = top + h * 0.36f
        listOf(0.35f, 0.65f).forEach { fraction ->
            val center = Offset(left + w * fraction + facing * w * 0.06f, eyeY)
            drawCircle(Color.White, eyeRadius, center)
            drawCircle(Color(0xFF111827), eyeRadius * 0.55f, center + Offset(facing * eyeRadius * 0.35f, 0f))
        }
    }

    private fun DrawScope.drawFlag(
        color: Color,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        big: Boolean,
    ) {
        val poleWidth = maxOf(2f, w * if (big) 0.12f else 0.25f)
        drawRect(Color(0xFFE5E7EB), Offset(left, top), Size(poleWidth, h))
        path.reset()
        path.moveTo(left + poleWidth, top + h * 0.05f)
        path.lineTo(left + poleWidth + w * if (big) 0.85f else 1.4f, top + h * 0.2f)
        path.lineTo(left + poleWidth, top + h * 0.38f)
        path.close()
        drawPath(path, color)
    }
}

internal fun ColorRgba.toColor(): Color = Color(argb.toInt())
