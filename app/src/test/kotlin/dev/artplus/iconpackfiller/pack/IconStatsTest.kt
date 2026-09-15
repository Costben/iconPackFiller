package dev.artplus.iconpackfiller.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconStatsTest {

    @Test
    fun `detects fully transparent as blank`() {
        val pixels = IntArray(16) { 0 }
        val stats = IconStats.fromPixels(pixels, 4, 4)
        assertEquals(1f, stats.alphaRatio)
        assertTrue(stats.isBlank)
        assertEquals(0, stats.dominantColor)
        assertTrue(stats.isSquare)
    }

    @Test
    fun `detects uniform color as blank`() {
        val color = 0xFF336699.toInt()
        val pixels = IntArray(16) { color }
        val stats = IconStats.fromPixels(pixels, 4, 4)
        assertEquals(0f, stats.alphaRatio)
        assertTrue(stats.isBlank)
        assertEquals(color, stats.dominantColor)
    }

    @Test
    fun `computes dominant color from opaque pixels`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val pixels = IntArray(10) { red } + IntArray(3) { blue } + IntArray(3) { 0 }
        val stats = IconStats.fromPixels(pixels, 4, 4)
        assertEquals(3f / 16f, stats.alphaRatio)
        assertTrue(!stats.isBlank)
        assertEquals(red, stats.dominantColor)
    }

    @Test
    fun `counts semi transparent as transparent`() {
        val semi = 0x80FF0000.toInt()
        val pixels = IntArray(4) { semi }
        val stats = IconStats.fromPixels(pixels, 2, 2)
        assertEquals(1f, stats.alphaRatio)
        assertTrue(stats.isBlank)
    }

    @Test
    fun `detects non square`() {
        val pixels = IntArray(8) { 0xFF000000.toInt() }
        val stats = IconStats.fromPixels(pixels, 4, 2)
        assertTrue(!stats.isSquare)
    }
}