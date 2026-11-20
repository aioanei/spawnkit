package dev.spawnkit.graphics

import dev.spawnkit.runtime.EntityHandle
import dev.spawnkit.runtime.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2D affine transform mapping `(x, y)` to `(a·x + c·y + tx, b·x + d·y + ty)`. Presentation-only: it uses
 * trigonometry and is never part of deterministic simulation state.
 */
public data class Affine2D(
    /** X scale/rotation term. */
    public val a: Double = 1.0,
    /** Y shear/rotation term of the x axis. */
    public val b: Double = 0.0,
    /** X shear/rotation term of the y axis. */
    public val c: Double = 0.0,
    /** Y scale/rotation term. */
    public val d: Double = 1.0,
    /** X translation. */
    public val tx: Double = 0.0,
    /** Y translation. */
    public val ty: Double = 0.0,
) {
    /** `this ∘ other`: applies [other] first, then this transform. */
    public operator fun times(other: Affine2D): Affine2D =
        Affine2D(
            a * other.a + c * other.b,
            b * other.a + d * other.b,
            a * other.c + c * other.d,
            b * other.c + d * other.d,
            a * other.tx + c * other.ty + tx,
            b * other.tx + d * other.ty + ty,
        )

    /** Maps a point. */
    public fun apply(
        x: Double,
        y: Double,
    ): Pair<Double, Double> = (a * x + c * y + tx) to (b * x + d * y + ty)

    /** The inverse transform, or `null` when this transform collapses space (zero scale). */
    public fun inverse(): Affine2D? {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine2D(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    /** Common transforms. */
    public companion object {
        /** The identity transform. */
        public val IDENTITY: Affine2D = Affine2D()

        /** Local transform of a [Transform2D]: translate, then rotate, then scale. */
        public fun of(transform: Transform2D): Affine2D {
            val radians = transform.rotation * PI / 180.0
            val cos = cos(radians)
            val sin = sin(radians)
            return Affine2D(
                cos * transform.scaleX,
                sin * transform.scaleX,
                -sin * transform.scaleY,
                cos * transform.scaleY,
                transform.x,
                transform.y,
            )
        }
    }
}

/** One drawable entry of a [RenderList], already in world space. */
public sealed interface DrawItem {
    /** The object drawn. */
    public val entity: EntityHandle

    /** Local-to-world transform of the object. */
    public val transform: Affine2D

    /** Draw layer. */
    public val layer: Long

    /** Width in local units, centred on the origin. */
    public val width: Double

    /** Height in local units, centred on the origin. */
    public val height: Double

    /** A filled shape. */
    public data class Shape(
        override val entity: EntityHandle,
        override val transform: Affine2D,
        override val layer: Long,
        override val width: Double,
        override val height: Double,
        /** `rectangle` or `ellipse`. */
        public val form: String,
        /** Fill color, ARGB. */
        public val color: Long,
    ) : DrawItem

    /** An image. */
    public data class Sprite(
        override val entity: EntityHandle,
        override val transform: Affine2D,
        override val layer: Long,
        override val width: Double,
        override val height: Double,
        /** Project asset ID. */
        public val asset: String,
        /** Tint, ARGB. */
        public val tint: Long,
    ) : DrawItem

    /** A line of text; [width] is estimated from its length (see [Text2D.ESTIMATED_ADVANCE]) and [height] is its size. */
    public data class Text(
        override val entity: EntityHandle,
        override val transform: Affine2D,
        override val layer: Long,
        override val width: Double,
        override val height: Double,
        /** Text shown. */
        public val text: String,
        /** Color, ARGB. */
        public val color: Long,
    ) : DrawItem
}

/** The camera used to view a scene: its world centre and zoom. */
public data class CameraView(
    /** World x at the centre of the view. */
    public val centerX: Double = 0.0,
    /** World y at the centre of the view. */
    public val centerY: Double = 0.0,
    /** Magnification. */
    public val zoom: Double = 1.0,
)

/**
 * Maps world coordinates to a pixel viewport of [widthPx] × [heightPx]: world units are scaled by [pixelsPerUnit]
 * times the camera zoom, and the camera centre lands in the middle of the viewport.
 */
public data class ViewTransform(
    /** Camera. */
    public val camera: CameraView,
    /** Viewport width in pixels. */
    public val widthPx: Double,
    /** Viewport height in pixels. */
    public val heightPx: Double,
    /** Pixels per world unit at zoom 1. */
    public val pixelsPerUnit: Double,
) {
    init {
        require(pixelsPerUnit > 0 && camera.zoom > 0) { "Scale must be positive" }
    }

    /** Pixels per world unit including zoom. */
    public val scale: Double get() = pixelsPerUnit * camera.zoom

    /** World-to-screen affine transform. */
    public val worldToScreen: Affine2D
        get() = Affine2D(scale, 0.0, 0.0, scale, widthPx / 2 - camera.centerX * scale, heightPx / 2 - camera.centerY * scale)

    /** Converts a screen point (pixels) to world coordinates. */
    public fun toWorld(
        x: Double,
        y: Double,
    ): Pair<Double, Double> = (camera.centerX + (x - widthPx / 2) / scale) to (camera.centerY + (y - heightPx / 2) / scale)
}

/**
 * Everything visible in a world, in draw order: ascending layer, then scene order (parents before children, siblings
 * in order, roots by slot). Build one per frame with [Graphics2D.renderList].
 */
public class RenderList internal constructor(
    /** Items in draw order. */
    public val items: List<DrawItem>,
    /** The active camera, or `null` when no active camera exists. */
    public val camera: CameraView?,
    private val worldTransforms: Map<EntityHandle, Affine2D>,
) {
    /** World transform of [entity] as resolved for this frame (identity when it has no placement). */
    public fun worldTransform(entity: EntityHandle): Affine2D = worldTransforms[entity] ?: Affine2D.IDENTITY

    /** The topmost visible item containing the world point, honouring rotation, scale and ellipse shapes. */
    public fun hitTest(
        x: Double,
        y: Double,
    ): EntityHandle? {
        for (item in items.asReversed()) {
            val inverse = item.transform.inverse() ?: continue
            val (lx, ly) = inverse.apply(x, y)
            val hw = item.width / 2
            val hh = item.height / 2
            if (hw <= 0 || hh <= 0) continue
            val inside =
                if (item is DrawItem.Shape && item.form == Shape2D.ELLIPSE) {
                    (lx / hw) * (lx / hw) + (ly / hh) * (ly / hh) <= 1.0
                } else {
                    abs(lx) <= hw && abs(ly) <= hh
                }
            if (inside) return item.entity
        }
        return null
    }
}

/** Builds the frame's [RenderList] from [world]. Reads only; never changes simulation state. */
public fun Graphics2D.renderList(world: World): RenderList {
    val transforms = LinkedHashMap<EntityHandle, Affine2D>()
    val order = ArrayList<EntityHandle>()

    // Pre-order traversal with an explicit stack, so deep hierarchies cannot overflow the call stack.
    val pending = ArrayDeque<Pair<EntityHandle, Affine2D>>()
    for (root in world.entities().asReversed()) if (world.parent(root) == null) pending.addLast(root to Affine2D.IDENTITY)
    while (pending.isNotEmpty()) {
        val (entity, parent) = pending.removeLast()
        val local = world.get(entity, transform)?.let(Affine2D::of) ?: Affine2D.IDENTITY
        val combined = parent * local
        transforms[entity] = combined
        order += entity
        for (child in world.children(entity).asReversed()) pending.addLast(child to combined)
    }

    val items = ArrayList<DrawItem>()
    var activeCamera: CameraView? = null
    for (entity in order) {
        val placed = transforms.getValue(entity)
        world.get(entity, shape)?.takeIf { it.visible }?.let {
            items += DrawItem.Shape(entity, placed, it.layer, it.width, it.height, it.form, it.color)
        }
        world.get(entity, sprite)?.takeIf { it.visible }?.let {
            items += DrawItem.Sprite(entity, placed, it.layer, it.width, it.height, it.asset, it.tint)
        }
        world.get(entity, text)?.takeIf { it.visible && it.text.isNotEmpty() }?.let {
            val width = it.size * Text2D.ESTIMATED_ADVANCE * it.text.length
            items += DrawItem.Text(entity, placed, it.layer, width, it.size, it.text, it.color)
        }
        if (activeCamera == null) {
            world.get(entity, camera)?.takeIf { it.active && it.zoom.isFinite() && it.zoom > 0.0 }?.let {
                // Values set natively bypass descriptor ranges; clamp so a bad zoom cannot break rendering.
                activeCamera = CameraView(placed.tx, placed.ty, it.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
            }
        }
    }
    // A stable sort keeps scene order within each layer.
    return RenderList(items.sortedBy { it.layer }, activeCamera, transforms)
}

private const val MIN_ZOOM = 0.01
private const val MAX_ZOOM = 100.0
