package dev.spawnkit.graphics

import dev.spawnkit.runtime.EditResult
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.World
import dev.spawnkit.runtime.edit
import dev.spawnkit.runtime.inspect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Graphics2DTest {
    private class Fixture {
        val builder = RuntimeBuilder()
        val graphics = Graphics2D(builder)
        val runtime = builder.build()
        val world: World = runtime.createWorld()

        fun box(
            transform: Transform2D,
            shape: Shape2D = Shape2D(),
        ) = world.spawn().also {
            world.set(it, graphics.transform, transform)
            world.set(it, graphics.shape, shape)
        }
    }

    private fun near(
        expected: Pair<Double, Double>,
        actual: Pair<Double, Double>,
    ) {
        assertTrue(abs(expected.first - actual.first) < 1e-9 && abs(expected.second - actual.second) < 1e-9, "$expected vs $actual")
    }

    @Test
    fun transformsComposeThroughTheHierarchy() {
        val fixture = Fixture()
        val parent = fixture.box(Transform2D(x = 10.0, y = 5.0, rotation = 90.0, scaleX = 2.0, scaleY = 2.0))
        val child = fixture.box(Transform2D(x = 1.0))
        fixture.world.setParent(child, parent)
        val list = fixture.graphics.renderList(fixture.world)
        // Child at local (1, 0) → scaled to (2, 0) → rotated 90° clockwise (y down) to (0, 2) → translated.
        near(10.0 to 7.0, list.worldTransform(child).apply(0.0, 0.0))
        near(10.0 to 5.0, list.worldTransform(parent).apply(0.0, 0.0))
        val inverse = checkNotNull(list.worldTransform(child).inverse())
        near(0.0 to 0.0, inverse.apply(10.0, 7.0))
        assertNull(Affine2D(0.0, 0.0, 0.0, 0.0).inverse())
    }

    @Test
    fun drawOrderIsLayerThenSceneOrder() {
        val fixture = Fixture()
        val top = fixture.box(Transform2D(), Shape2D(layer = 5))
        val back = fixture.box(Transform2D(), Shape2D(layer = -1))
        val parent = fixture.box(Transform2D())
        val child = fixture.box(Transform2D())
        fixture.world.setParent(child, parent)
        val hidden = fixture.box(Transform2D(), Shape2D(visible = false))
        val order =
            fixture.graphics
                .renderList(fixture.world)
                .items
                .map { it.entity }
        assertEquals(listOf(back, parent, child, top), order)
        assertTrue(hidden !in order)
    }

    @Test
    fun hitTestingFindsTheTopmostObjectHonouringRotationScaleAndEllipses() {
        val fixture = Fixture()
        val wide = fixture.box(Transform2D(x = 0.0, y = 0.0, rotation = 90.0), Shape2D(width = 4.0, height = 1.0))
        val circle =
            fixture.box(
                Transform2D(x = 10.0, y = 0.0, scaleX = 2.0, scaleY = 2.0),
                Shape2D(form = Shape2D.ELLIPSE, width = 1.0, height = 1.0, layer = 1),
            )
        val list = fixture.graphics.renderList(fixture.world)
        assertEquals(wide, list.hitTest(0.0, 1.8), "Rotated 90°, the 4-wide box is tall")
        assertNull(list.hitTest(1.8, 0.0))
        assertEquals(circle, list.hitTest(10.9, 0.0), "Scaled circle has radius 1")
        assertNull(list.hitTest(10.8, 0.8), "Outside the ellipse but inside its bounding box")
        val cover = fixture.box(Transform2D(x = 10.0), Shape2D(width = 3.0, height = 3.0, layer = 2))
        assertEquals(cover, fixture.graphics.renderList(fixture.world).hitTest(10.0, 0.0), "Higher layer wins")
    }

    @Test
    fun cameraAndViewTransformConvertBetweenScreenAndWorld() {
        val fixture = Fixture()
        val cam = fixture.world.spawn()
        fixture.world.set(cam, fixture.graphics.transform, Transform2D(x = 5.0, y = 3.0))
        fixture.world.set(cam, fixture.graphics.camera, Camera2D(zoom = 2.0))
        val list = fixture.graphics.renderList(fixture.world)
        val camera = checkNotNull(list.camera)
        assertEquals(CameraView(5.0, 3.0, 2.0), camera)
        val view = ViewTransform(camera, widthPx = 800.0, heightPx = 600.0, pixelsPerUnit = 32.0)
        near(400.0 to 300.0, view.worldToScreen.apply(5.0, 3.0))
        near(464.0 to 300.0, view.worldToScreen.apply(6.0, 3.0))
        near(6.0 to 3.0, view.toWorld(464.0, 300.0))
    }

    @Test
    fun presentationComponentsAreEditableThroughTheGenericInspector() {
        val fixture = Fixture()
        val entity = fixture.box(Transform2D())
        val inspected = fixture.runtime.inspect(fixture.world, entity).map { it.descriptor.component.id }
        assertEquals(listOf("graphics:shape", "graphics:transform"), inspected)
        assertEquals(EditResult.Applied(0xFF4CAF50), fixture.runtime.edit(fixture.world, entity, "graphics:shape", "color", 0xFF2196F3))
        assertIs<EditResult.Rejected>(fixture.runtime.edit(fixture.world, entity, "graphics:shape", "color", 0x1FFFFFFFFL))
        assertIs<EditResult.Rejected>(fixture.runtime.edit(fixture.world, entity, "graphics:shape", "form", "triangle"))
        assertEquals(EditResult.Applied(0.0), fixture.runtime.edit(fixture.world, entity, "graphics:transform", "x", 3.5))
        assertEquals(
            3.5,
            fixture.graphics
                .renderList(fixture.world)
                .items
                .single()
                .transform.tx,
        )
        // Checkpoints include presentation state.
        val restored = fixture.runtime.restore(fixture.world.snapshot())
        assertEquals(fixture.world.stateHash(), restored.stateHash())
    }
}
