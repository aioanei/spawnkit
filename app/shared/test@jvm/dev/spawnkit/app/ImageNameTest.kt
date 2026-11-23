package dev.spawnkit.app

import dev.spawnkit.app.images.imageName
import kotlin.test.Test
import kotlin.test.assertEquals

/** Names of imported pictures (C15b). */
class ImageNameTest {
    @Test
    fun fileNamesBecomeImageNames() {
        assertEquals("Logo", imageName("C:\\pictures\\Logo.png"))
        assertEquals("hero 2", imageName("/storage/hero 2.jpeg"))
        assertEquals("Picture", imageName("1000000033"), "photo pickers hide names")
        assertEquals("Picture", imageName(""))
    }
}
