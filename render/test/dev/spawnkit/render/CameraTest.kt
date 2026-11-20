package dev.spawnkit.render

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraTest {
    @Test
    fun centersOnTargetInsideScene() {
        assertEquals(CameraOrigin(22.0, 2.0), cameraOrigin(32.0, 8.0, viewW = 20.0, viewH = 12.0, sceneW = 64.0, sceneH = 20.0))
    }

    @Test
    fun clampsAtSceneLeftEdge() {
        assertEquals(0.0, cameraOrigin(2.0, 5.0, viewW = 20.0, viewH = 11.25, sceneW = 64.0, sceneH = 14.0).x)
    }

    @Test
    fun clampsAtSceneRightEdge() {
        assertEquals(44.0, cameraOrigin(63.0, 5.0, viewW = 20.0, viewH = 11.25, sceneW = 64.0, sceneH = 14.0).x)
    }

    @Test
    fun clampsVertically() {
        assertEquals(14.0 - 11.25, cameraOrigin(10.0, 13.0, viewW = 20.0, viewH = 11.25, sceneW = 64.0, sceneH = 14.0).y)
        assertEquals(0.0, cameraOrigin(10.0, -5.0, viewW = 20.0, viewH = 11.25, sceneW = 64.0, sceneH = 14.0).y)
    }

    @Test
    fun centersSmallScene() {
        val origin = cameraOrigin(3.0, 3.0, viewW = 20.0, viewH = 11.25, sceneW = 10.0, sceneH = 6.0)
        assertEquals(-5.0, origin.x)
        assertEquals(-2.625, origin.y)
    }
}
