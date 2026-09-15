package dev.artplus.iconpackfiller.reference

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentTrimmerTest {

    private val opaqueWhite = 0xFFFFFFFF.toInt()
    private val subject = 0xFF3366FF.toInt()

    private fun canvas(
        w: Int = 64,
        h: Int = 64,
        fill: (x: Int, y: Int) -> Int,
    ): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = fill(x, y)
        return pixels
    }

    @Test
    fun `trims transparent border`() {
        // 中央 20..44 的方块，其余全透明
        val pixels = canvas { x, y ->
            if (x in 20 until 44 && y in 20 until 44) subject else 0x00000000
        }
        val box = ContentTrimmer.contentBox(pixels, 64, 64)
        assertNotNull(box)
        assertEquals(20, box.left)
        assertEquals(20, box.top)
        assertEquals(44, box.right)
        assertEquals(44, box.bottom)
    }

    @Test
    fun `trims flat white border when corners are uniform`() {
        // 白底 + 中央内容（模拟 Aura 满幅白底图标：白边很窄）
        val pixels = canvas { x, y ->
            if (x in 4 until 60 && y in 4 until 60) subject else opaqueWhite
        }
        val box = ContentTrimmer.contentBox(pixels, 64, 64)
        assertNotNull(box)
        assertEquals(4, box.left)
        assertTrue(box.width == 56)
    }

    @Test
    fun `does not trim when full-color icon has uniform corners`() {
        // 满幅纯色图标（四角同色但内容遍布）：裁量超限 -> 不裁
        val pixels = canvas { _, _ -> 0xFF111111.toInt() }
        assertNull(ContentTrimmer.contentBox(pixels, 64, 64))
    }

    /**
     * 白底 + 小图形时颜色裁边会(错误地)裁掉大量白底。
     * MAX_OPAQUE_TRIM_RATIO=15% 会阻止这种裁剪（本例白边 25% > 15%）。
     */
    @Test
    fun `refuses opaque trim beyond safety ratio`() {
        val pixels = canvas { x, y ->
            if (x in 16 until 48 && y in 16 until 48) subject else opaqueWhite
        }
        assertNull(ContentTrimmer.contentBox(pixels, 64, 64))
    }

    @Test
    fun `does not trim icon without any border`() {
        val pixels = canvas { _, _ -> subject }
        assertNull(ContentTrimmer.contentBox(pixels, 64, 64))
    }

    /**
     * 四角不同色（例如渐变或斜切设计）时不敢按颜色裁，返回 null。
     */
    @Test
    fun `mixed corner colors are left alone`() {
        val pixels = canvas { x, y -> if (x < 32 && y < 32) 0xFFFF0000.toInt() else 0xFF00FF00.toInt() }
        assertNull(ContentTrimmer.contentBox(pixels, 64, 64))
    }

    @Test
    fun `rejects degenerate sizes`() {
        assertNull(ContentTrimmer.contentBox(IntArray(0), 0, 0))
    }

    @Test
    fun `tolerates slight noise on transparent border`() {
        // 边界处有 alpha=4 的噪声（低于阈值 8）应被忽略
        val pixels = canvas { x, y ->
            when {
                x in 20 until 44 && y in 20 until 44 -> subject
                x == 19 || y == 19 -> 0x04000000
                else -> 0x00000000
            }
        }
        val box = ContentTrimmer.contentBox(pixels, 64, 64)
        assertNotNull(box)
        assertEquals(20, box.left)
    }
}
