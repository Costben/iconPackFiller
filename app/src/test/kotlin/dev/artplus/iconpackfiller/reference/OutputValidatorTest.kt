package dev.artplus.iconpackfiller.reference

import dev.artplus.iconpackfiller.pack.IconStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerceptualHashTest {

    private fun solid(width: Int, height: Int, color: Int): IntArray =
        IntArray(width * height) { color }

    private fun gradient(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val v = (i * 255 / (width * height - 1))
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }

    @Test
    fun `identical images have zero distance`() {
        val pixels = gradient(16, 16)
        val a = PerceptualHash.hashOf(pixels, 16, 16)
        val b = PerceptualHash.hashOf(pixels, 16, 16)
        assertEquals(0, PerceptualHash.hamming(a, b))
        assertEquals(1f, PerceptualHash.similarity(a, b))
    }

    @Test
    fun `solid colors are degenerate for ahash`() {
        val black = PerceptualHash.hashOf(solid(8, 8, 0xFF000000.toInt()), 8, 8)
        val white = PerceptualHash.hashOf(solid(8, 8, 0xFFFFFFFF.toInt()), 8, 8)
        assertEquals(0, PerceptualHash.hamming(black, white))
    }

    @Test
    fun `solid differs from split image`() {
        val split = IntArray(32 * 32) { i ->
            if ((i % 32) < 16) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val solid = PerceptualHash.hashOf(solid(32, 32, 0xFF808080.toInt()), 32, 32)
        val splitHash = PerceptualHash.hashOf(split, 32, 32)
        assertTrue(PerceptualHash.hamming(solid, splitHash) > 16)
    }

    @Test
    fun `similar gradients are close`() {
        val a = PerceptualHash.hashOf(gradient(32, 32), 32, 32)
        val shifted = IntArray(32 * 32) { i -> gradient(32, 32)[(i + 1) % (32 * 32)] }
        val b = PerceptualHash.hashOf(shifted, 32, 32)
        assertTrue(PerceptualHash.similarity(a, b) > 0.5f)
    }

    @Test
    fun `gray8x8 rejects wrong size`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PerceptualHash.gray8x8(IntArray(10), 5, 3)
        }
    }

    @Test
    fun `colorDistance basics`() {
        assertEquals(0, PerceptualHash.colorDistance(0xFF112233.toInt(), 0xFF112233.toInt()))
        assertEquals(2, PerceptualHash.colorDistance(0xFF000000.toInt(), 0xFF000101.toInt()))
    }
}

class OutputValidatorTest {

    private fun stats(
        width: Int = 512,
        height: Int = 512,
        alphaRatio: Float = 0.3f,
        blank: Boolean = false,
    ) = IconStats(width, height, alphaRatio, 0xFF336699.toInt(), blank)

    private val hash = PerceptualHash.hash(IntArray(64) { if (it % 2 == 0) 200 else 50 })

    @Test
    fun `valid output passes`() {
        val result = OutputValidator.validate(stats(), hash, hash)
        assertTrue(result is OutputValidator.Result.Valid)
    }

    @Test
    fun `non square rejected`() {
        val result = OutputValidator.validate(stats(width = 512, height = 300), hash, hash)
        assertTrue(result is OutputValidator.Result.Invalid)
        assertTrue((result as OutputValidator.Result.Invalid).reason.contains("正方形"))
    }

    @Test
    fun `too small rejected`() {
        val result = OutputValidator.validate(stats(width = 256, height = 256), hash, hash)
        assertTrue(result is OutputValidator.Result.Invalid)
        assertTrue((result as OutputValidator.Result.Invalid).reason.contains("边长不足"))
    }

    @Test
    fun `blank rejected`() {
        val result = OutputValidator.validate(stats(blank = true), hash, hash)
        assertTrue(result is OutputValidator.Result.Invalid)
    }

    @Test
    fun `nearly transparent rejected`() {
        val result = OutputValidator.validate(stats(alphaRatio = 0.99f), hash, hash)
        assertTrue(result is OutputValidator.Result.Invalid)
        assertTrue((result as OutputValidator.Result.Invalid).reason.contains("空白"))
    }

    /**
     * 阈值来自真实标定：Aura 包内作者本人的重绘 vs 源图为 min 0.08 / 中位 0.25 / max 0.47。
     * 因此 0.50 这种「风格化但仍同源」的距离必须通过，否则会误杀正常重绘。
     */
    @Test
    fun `stylized but recognizable output accepted at calibrated threshold`() {
        // 距离 0.50：旧阈值 0.45 会误判为「丢品牌识别度」
        val stylized = PerceptualHash.hash(IntArray(64) { if (it < 32) 200 else 50 })
        val result = OutputValidator.validate(stats(), stylized, hash)
        assertTrue(result is OutputValidator.Result.Valid)
    }

    @Test
    fun `completely unrelated output rejected`() {
        // 距离 1.0：与源图完全无关联
        val opposite = hash.inv()
        val result = OutputValidator.validate(stats(), opposite, hash)
        assertTrue(result is OutputValidator.Result.Invalid)
        assertTrue((result as OutputValidator.Result.Invalid).reason.contains("品牌识别度"))
    }

    @Test
    fun `threshold is configurable`() {
        val stylized = PerceptualHash.hash(IntArray(64) { if (it < 32) 200 else 50 })
        val strict = OutputValidator.validate(
            stats(),
            stylized,
            hash,
            config = OutputValidator.Config(maxHashDistance = 0.4f),
        )
        assertTrue(strict is OutputValidator.Result.Invalid)
    }

    @Test
    fun `alpha mismatch rejected when pack typical provided`() {
        val result = OutputValidator.validate(stats(alphaRatio = 0.05f), hash, hash, packTypicalAlpha = 0.9f)
        assertTrue(result is OutputValidator.Result.Invalid)
        assertTrue((result as OutputValidator.Result.Invalid).reason.contains("alpha"))
    }

    @Test
    fun `alpha match accepted`() {
        val result = OutputValidator.validate(stats(alphaRatio = 0.5f), hash, hash, packTypicalAlpha = 0.6f)
        assertTrue(result is OutputValidator.Result.Valid)
    }

    @Test
    fun `custom config relaxes size`() {
        val result = OutputValidator.validate(
            stats(width = 256, height = 256),
            hash,
            hash,
            config = OutputValidator.Config(minSize = 128),
        )
        assertTrue(result is OutputValidator.Result.Valid)
    }
}