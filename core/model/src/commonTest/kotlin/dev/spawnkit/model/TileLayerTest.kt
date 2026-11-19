package dev.spawnkit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileLayerTest {
    private val layer = TileLayer(LayerId("l"), "Layer", listOf("#..", ".=."))

    @Test
    fun charAtReadsRowsAndColumns() {
        assertEquals('#', layer.charAt(0, 0))
        assertEquals('=', layer.charAt(1, 1))
    }

    @Test
    fun charAtOutsideIsEmpty() {
        assertEquals('.', layer.charAt(-1, 0))
        assertEquals('.', layer.charAt(0, 5))
        assertEquals('.', layer.charAt(3, 0))
    }

    @Test
    fun validateRowsAcceptsMatchingSize() {
        assertEquals(emptyList(), layer.validateRows(width = 3, height = 2))
    }

    @Test
    fun validateRowsReportsWrongWidth() {
        val errors = TileLayer(LayerId("l"), "Layer", listOf("#########", "..........")).validateRows(width = 10, height = 2)
        assertEquals(1, errors.size)
        assertTrue("row 0" in errors.single(), errors.single())
    }

    @Test
    fun validateRowsReportsWrongHeight() {
        val errors = layer.validateRows(width = 3, height = 4)
        assertTrue(errors.any { "rows" in it }, errors.toString())
    }
}
