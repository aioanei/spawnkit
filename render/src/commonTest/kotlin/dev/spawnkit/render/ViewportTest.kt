package dev.spawnkit.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {
    @Test
    fun ultraWideLetterboxesHorizontally() {
        val t = computeViewport(containerW = 3440f, containerH = 1000f, virtualW = 20f, virtualH = 11.25f)
        assertEquals(1000f / 11.25f, t.scale, 1e-3f)
        assertTrue(t.offsetX > 0f)
        assertEquals(0f, t.offsetY, 1e-3f)
        assertEquals(1000f, t.heightPx, 1e-3f)
        assertEquals(3440f, t.widthPx + 2 * t.offsetX, 1e-2f)
    }

    @Test
    fun tallPhoneLetterboxesVertically() {
        val t = computeViewport(containerW = 1080f, containerH = 2400f, virtualW = 20f, virtualH = 11.25f)
        assertEquals(0f, t.offsetX, 1e-3f)
        assertTrue(t.offsetY > 0f)
        assertEquals(1080f, t.widthPx, 1e-3f)
        assertEquals(2400f, t.heightPx + 2 * t.offsetY, 1e-2f)
    }

    @Test
    fun exactAspectHasNoBars() {
        val t = computeViewport(containerW = 1600f, containerH = 900f, virtualW = 20f, virtualH = 11.25f)
        assertEquals(80f, t.scale, 1e-3f)
        assertEquals(0f, t.offsetX, 1e-3f)
        assertEquals(0f, t.offsetY, 1e-3f)
    }

    @Test
    fun degenerateContainerYieldsZeroScale() {
        val t = computeViewport(containerW = 0f, containerH = 500f, virtualW = 20f, virtualH = 11.25f)
        assertEquals(0f, t.scale)
    }
}
