package dev.artplus.iconpackfiller.reference

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundRemovalTest {

    private val opaqueWhite = 0xFFFFFFFF.toInt()
    private val checkerLight = 0xFFC3C3C3.toInt()
    private val checkerDark = 0xFF9E9E9E.toInt()
    private val subject = 0xFF00A0FF.toInt()

    /**
     * 合成一张 w x h 图：背景由 [background] 决定，中央 [inner] 矩形为主体。
     */
    private fun canvas(
        w: Int = 64,
        h: Int = 64,
        inset: Int = 16,
        inner: Int = subject,
        background: (x: Int, y: Int) -> Int,
    ): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isInner = x >= inset && x < w - inset && y >= inset && y < h - inset
                pixels[y * w + x] = if (isInner) inner else background(x, y)
            }
        }
        return pixels
    }

    private fun alphaAt(pixels: IntArray, w: Int, x: Int, y: Int) = (pixels[y * w + x] ushr 24) and 0xFF

    @Test
    fun `removes flat white background`() {
        val pixels = canvas { _, _ -> opaqueWhite }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, 64, 0, 0))
        assertEquals(0, alphaAt(result.pixels, 64, 63, 63))
        // 主体保留
        assertEquals(255, alphaAt(result.pixels, 64, 32, 32))
        assertEquals(subject, result.pixels[32 * 64 + 32])
    }

    /**
     * 最关键的真实场景：模型把"透明"画成了棋盘格。
     */
    @Test
    fun `removes two tone checkerboard background`() {
        val pixels = canvas { x, y -> if ((x / 4 + y / 4) % 2 == 0) checkerLight else checkerDark }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, 64, 0, 0), "浅格应被移除")
        assertEquals(0, alphaAt(result.pixels, 64, 4, 0), "深格应被移除")
        assertEquals(0, alphaAt(result.pixels, 64, 63, 63))
        assertEquals(255, alphaAt(result.pixels, 64, 32, 32), "主体必须保留")
    }

    @Test
    fun `keeps subject color untouched`() {
        val pixels = canvas { _, _ -> opaqueWhite }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied)
        val center = result.pixels[32 * 64 + 32]
        assertEquals(subject, center)
        assertEquals(0xFF, (center ushr 24) and 0xFF)
    }

    @Test
    fun `uniform whole image is rejected as degenerate`() {
        // 整图同色：无法区分主体与背景，放弃并交由 OutputValidator 判为空白
        val pixels = IntArray(64 * 64) { subject }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertFalse(result.applied)
        assertEquals("整图均为同色（疑似模型返回空白）", result.reason)
        assertEquals(subject, result.pixels[0])
    }

    /**
     * 小主体 + 大留白是合法输入（图标自然有大片透明区），移除率可以很高。
     */
    @Test
    fun `small subject with large padding is still removed`() {
        val pixels = canvas(inset = 28) { _, _ -> opaqueWhite } // 主体 8x8，留白 94%
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, 64, 0, 32))
        assertEquals(255, alphaAt(result.pixels, 64, 32, 32), "小主体必须保留")
        assertTrue(result.removedRatio > 0.9f, "ratio=${result.removedRatio}")
    }

    /**
     * 主体与边框连通（例如主体自己顶到边缘）时必须放弃，否则会吃掉主体。
     */
    @Test
    fun `gives up when subject touches border`() {
        val pixels = IntArray(64 * 64) { opaqueWhite }
        // 一条从边框连到中心的白色通路之外，把主体铺满整行，使背景与主体连通
        for (x in 0 until 64) {
            for (y in 28 until 36) pixels[y * 64 + x] = subject
        }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        // 背景区域（上下白边）仍可安全移除，占比不会超上限
        if (result.applied) {
            assertEquals(255, alphaAt(result.pixels, 64, 32, 32), "主体像素不得被清除")
        }
    }

    @Test
    fun `tolerates near white noise`() {
        // 背景是接近白色但带轻微噪声（模型输出常见的压缩痕迹）
        val pixels = canvas { x, y ->
            val delta = ((x * 7 + y * 13) % 9) - 4
            (0xFF shl 24) or ((0xFE + delta).coerceIn(0, 255) shl 16) or
                ((0xFE + delta).coerceIn(0, 255) shl 8) or (0xFE + delta).coerceIn(0, 255)
        }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, 64, 1, 1))
        assertEquals(255, alphaAt(result.pixels, 64, 32, 32))
    }

    /**
     * 真实事故复现：模型在图标下画了一圈"软阴影"（向内严格变暗的灰渐变），
     * 第一遍（容差 30）吃不掉，在深色壁纸上留下一圈浅灰光晕。
     * 第二遍必须沿梯度把它跟到底、吃干净。
     */
    @Test
    fun `removes soft shadow gradient around subject`() {
        val w = 64
        val subjectLo = 20
        val subjectHi = 44 // 主体 24x24
        val pixels = IntArray(w * w)
        for (y in 0 until w) {
            for (x in 0 until w) {
                val dx = when {
                    x < subjectLo -> subjectLo - x
                    x >= subjectHi -> x - subjectHi + 1
                    else -> 0
                }
                val dy = when {
                    y < subjectLo -> subjectLo - y
                    y >= subjectHi -> y - subjectHi + 1
                    else -> 0
                }
                val k = maxOf(dx, dy) // 到主体的切比雪夫距离
                pixels[y * w + x] = when {
                    k == 0 -> subject
                    // 连续渐变（无断崖）：靠白底一侧最浅、靠主体一侧最深，
                    // 第一遍从白底吃掉浅头部，第二遍沿梯度跟到底
                    k <= 8 -> gray(192 + 7 * k)
                    else -> opaqueWhite
                }
            }
        }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, w, w)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, w, 12, 32), "阴影外环应被移除")
        assertEquals(0, alphaAt(result.pixels, w, 19, 32), "阴影内环应被移除")
        assertEquals(0, alphaAt(result.pixels, w, 0, 0), "白底应被移除")
        assertEquals(255, alphaAt(result.pixels, w, 32, 32), "主体必须保留")
        assertEquals(subject, result.pixels[32 * w + 32])
    }

    /**
     * 第二遍的安全护栏：大面积浅色平涂（如米色图标底色）是"平"的，
     * 亮度走几步就进入平台，必须停下来，最多侵蚀边缘几个像素。
     */
    @Test
    fun `plateau stops flood inside flat light fill`() {
        val w = 64
        val beige = (0xFF shl 24) or (232 shl 16) or (222 shl 8) or 208
        val pixels = IntArray(w * w) { opaqueWhite }
        for (y in 12 until 52) {
            for (x in 0 until 40) pixels[y * w + x] = beige
        }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, w, w)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, w, 39, 32), "色块边缘混色应被吃掉")
        // 平台期截断后剩下的贴边 1px 由 despill 变软（淡边比不透明灰环更隐形）
        val softened = alphaAt(result.pixels, w, 35, 32)
        assertTrue(softened in 20..70, "贴边应被去键色变软，alpha=$softened")
        assertEquals(255, alphaAt(result.pixels, w, 30, 32), "色块深处必须保留")
    }

    /**
     * 低饱和主体（如棕色）饱和度会通过第二遍的饱和门，但色差门把它拦下：
     * 两道门是"与"关系，缺一不可。
     */
    @Test
    fun `brownish low saturation subject survives`() {
        val brown = (0xFF shl 24) or (112 shl 16) or (94 shl 8) or 85
        val pixels = canvas(inner = brown) { _, _ -> opaqueWhite }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied, result.reason)
        assertEquals(255, alphaAt(result.pixels, 64, 32, 32))
        assertEquals(brown, result.pixels[32 * 64 + 32])
    }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /**
     * 键色底的核心场景：品红底 + 饱和主体 + 1px 品红混色环。
     * 混色环是饱和色，第二遍拦不住，必须由 despill 按含键量还原：
     * 半透明 + 扣除品红染色，露出主体蓝。
     */
    @Test
    fun `despills magenta fringe on chroma key background`() {
        val w = 64
        val magenta = (0xFF shl 24) or (255 shl 16) or 255
        // 蓝主体 25% + 品红 75% 的混色（抗锯齿边缘的典型形态）
        val blend = (0xFF shl 24) or (191 shl 16) or (40 shl 8) or 255
        val pixels = IntArray(w * w) { magenta }
        for (y in 15 until 49) {
            for (x in 15 until 49) {
                val edge = x == 15 || x == 48 || y == 15 || y == 48
                pixels[y * w + x] = if (edge) blend else subject
            }
        }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, w, w)
        assertTrue(result.applied, result.reason)
        assertEquals(0, alphaAt(result.pixels, w, 0, 0), "品红底应被移除")
        assertEquals(255, alphaAt(result.pixels, w, 32, 32), "主体必须保留")
        assertEquals(subject, result.pixels[32 * w + 32])
        val ring = result.pixels[32 * w + 15]
        val ringAlpha = (ring ushr 24) and 0xFF
        assertTrue(ringAlpha in 30..90, "混色环应变半透明，alpha=$ringAlpha")
        assertTrue(((ring shr 16) and 0xFF) < 40, "红色键色应被扣除")
        assertTrue((ring and 0xFF) > 200, "蓝色主体应保留")
    }

    /**
     * 灰图标在白底上的边缘：despill 只软化贴边 1px，深处不动。
     * （中灰方块先被第二遍侵蚀 4px，新的边界再被变软。）
     */
    @Test
    fun `despill softens gray icon edge without eating inward`() {
        val w = 64
        val pixels = IntArray(w * w) { opaqueWhite }
        for (y in 16 until 48) {
            for (x in 16 until 48) pixels[y * w + x] = gray(200)
        }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, w, w)
        assertTrue(result.applied, result.reason)
        val softened = alphaAt(result.pixels, w, 20, 32)
        assertTrue(softened in 30..70, "新边界应变软，alpha=$softened")
        assertEquals(255, alphaAt(result.pixels, w, 24, 32), "深处必须保持不透明")
        assertEquals(255, alphaAt(result.pixels, w, 32, 32))
    }

    @Test
    fun `too small image is not processed`() {
        val result = BackgroundRemoval.removeBorderConnectedBackground(IntArray(4), 2, 2)
        assertFalse(result.applied)
        assertEquals("尺寸过小", result.reason)
    }

    @Test
    fun `mismatched pixel count throws`() {
        var threw = false
        try {
            BackgroundRemoval.removeBorderConnectedBackground(IntArray(10), 4, 4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `already transparent background is a no-op`() {
        val pixels = canvas { _, _ -> 0x00000000 }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertFalse(result.applied)
        assertEquals("未检出背景（占比过低）", result.reason)
    }

    @Test
    fun `removed ratio is reported`() {
        val pixels = canvas(inset = 16) { _, _ -> opaqueWhite }
        val result = BackgroundRemoval.removeBorderConnectedBackground(pixels, 64, 64)
        assertTrue(result.applied)
        // 64x64 去掉 32x32 主体 = 3072/4096 = 0.75
        assertTrue(result.removedRatio in 0.7f..0.8f, "ratio=${result.removedRatio}")
    }
}
