package dev.spawnkit.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.spawnkit.graphics.Camera2D
import dev.spawnkit.graphics.Graphics2D
import dev.spawnkit.graphics.Shape2D
import dev.spawnkit.graphics.Transform2D
import dev.spawnkit.graphics.renderList
import dev.spawnkit.runtime.RuntimeBuilder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Renders a general-runtime scene offscreen with Skia and checks actual pixels. */
class RuntimeCanvasRenderTest {
    @Test
    fun runtimeSceneRendersShapesInLayerOrderThroughTheCamera() {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val world = builder.build().createWorld()
        val camera = world.spawn()
        world.set(camera, graphics.transform, Transform2D(x = 0.0, y = 0.0))
        world.set(camera, graphics.camera, Camera2D(zoom = 1.0))
        val ground = world.spawn()
        world.set(ground, graphics.transform, Transform2D(y = 2.0))
        world.set(ground, graphics.shape, Shape2D(width = 12.0, height = 1.0, color = 0xFF8D6E63))
        val sun = world.spawn()
        world.set(sun, graphics.transform, Transform2D(x = 3.0, y = -2.0))
        world.set(sun, graphics.shape, Shape2D(form = Shape2D.ELLIPSE, width = 1.5, height = 1.5, color = 0xFFFFC107))
        val hero = world.spawn()
        world.set(hero, graphics.transform, Transform2D(x = 0.0, y = 1.5, rotation = 15.0))
        world.set(hero, graphics.shape, Shape2D(width = 1.0, height = 1.0, color = 0xFF2196F3, layer = 1))
        val frame = graphics.renderList(world)

        val scene =
            ImageComposeScene(width = 640, height = 360, density = Density(1f)) {
                RuntimeCanvas(
                    frame,
                    Modifier.fillMaxSize(),
                    pixelsPerUnit = 40f,
                    selection = setOf(hero),
                    style = CanvasStyle(showGrid = true),
                )
            }
        val image = scene.render()
        scene.close()
        val bitmap = Bitmap.makeFromImage(image)
        // World (0, 2) is the ground centre: 320 + 0·40, 180 + 2·40.
        assertEquals(0xFF8D6E63.toInt(), bitmap.getColor(320 - 100, 260))
        // World (0, 1.75) lies inside both the ground and the hero; the hero (layer 1) must be drawn on top.
        assertEquals(0xFF2196F3.toInt(), bitmap.getColor(320, 250))
        // The sun's centre at world (3, -2) → pixel (440, 100).
        assertEquals(0xFFFFC107.toInt(), bitmap.getColor(440, 100))
        val out = File("build/screenshots").apply { mkdirs() }.resolve("runtime-canvas.png")
        out.writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
    }
}
