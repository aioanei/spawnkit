package dev.spawnkit.render

/**
 * Where the game's virtual viewport lands inside a container, after uniform scaling and letterboxing.
 *
 * @property scale pixels per world unit (tile).
 * @property offsetX left letterbox width in pixels.
 * @property offsetY top letterbox height in pixels.
 * @property widthPx width of the game area in pixels.
 * @property heightPx height of the game area in pixels.
 */
public data class ViewportTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val widthPx: Float,
    val heightPx: Float,
)

/** Fits a [virtualW] × [virtualH] tile viewport into a container, keeping the aspect ratio (never cropping). */
public fun computeViewport(
    containerW: Float,
    containerH: Float,
    virtualW: Float,
    virtualH: Float,
): ViewportTransform {
    if (containerW <= 0f || containerH <= 0f || virtualW <= 0f || virtualH <= 0f) {
        return ViewportTransform(0f, 0f, 0f, 0f, 0f)
    }
    val scale = minOf(containerW / virtualW, containerH / virtualH)
    val width = virtualW * scale
    val height = virtualH * scale
    return ViewportTransform(scale, (containerW - width) / 2f, (containerH - height) / 2f, width, height)
}

/** Top-left corner of the camera in world units. */
public data class CameraOrigin(
    val x: Double,
    val y: Double,
)

/**
 * Camera that centers on a target and stays inside the scene. Scenes smaller than the view are centered instead.
 */
public fun cameraOrigin(
    targetX: Double,
    targetY: Double,
    viewW: Double,
    viewH: Double,
    sceneW: Double,
    sceneH: Double,
): CameraOrigin = CameraOrigin(axisOrigin(targetX, viewW, sceneW), axisOrigin(targetY, viewH, sceneH))

private fun axisOrigin(
    target: Double,
    view: Double,
    scene: Double,
): Double = if (scene <= view) (scene - view) / 2.0 else (target - view / 2.0).coerceIn(0.0, scene - view)
