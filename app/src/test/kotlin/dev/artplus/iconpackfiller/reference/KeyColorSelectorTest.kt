package dev.artplus.iconpackfiller.reference

import org.junit.Test
import kotlin.test.assertEquals

class KeyColorSelectorTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun solid(w: Int = 64, h: Int = 64, color: Int): IntArray = IntArray(w * h) { color }

    @Test
    fun `red icon avoids red key`() {
        assertEquals(KeyColor.GREEN, KeyColorSelector.select(solid(color = red), 64, 64))
    }

    @Test
    fun `green icon avoids green key`() {
        assertEquals(KeyColor.RED, KeyColorSelector.select(solid(color = green), 64, 64))
    }

    @Test
    fun `blue icon avoids blue key`() {
        assertEquals(KeyColor.RED, KeyColorSelector.select(solid(color = blue), 64, 64))
    }

    /**
     * 判定只看最外圈：红色只出现在内部时，红键色仍然可用
     * （整图 maximin 会误杀这种情形）。
     */
    @Test
    fun `ring criterion allows key matching interior only`() {
        val pixels = solid(color = blue)
        for (y in 24 until 40) {
            for (x in 24 until 40) pixels[y * 64 + x] = red
        }
        assertEquals(KeyColor.RED, KeyColorSelector.select(pixels, 64, 64))
    }

    /**
     * adaptive 图标四角透明、外圈无内容时回退到整图判定。
     */
    @Test
    fun `transparent ring falls back to whole icon`() {
        val pixels = IntArray(64 * 64) { 0x00000000 }
        for (y in 8 until 56) {
            for (x in 8 until 56) pixels[y * 64 + x] = red
        }
        assertEquals(KeyColor.GREEN, KeyColorSelector.select(pixels, 64, 64))
    }

    @Test
    fun `fully transparent returns default`() {
        assertEquals(KeyColor.DEFAULT, KeyColorSelector.select(IntArray(64 * 64), 64, 64))
    }

    @Test
    fun `mismatched size returns default`() {
        assertEquals(KeyColor.DEFAULT, KeyColorSelector.select(IntArray(10), 4, 4))
    }
}
