package dev.artplus.iconpackfiller.reference

import kotlin.math.abs

/**
 * 感知哈希（aHash 64bit）。纯 JVM，可单测。
 *
 * 实现：缩到 8x8 灰度，取均值二值化为 64bit。汉明距离用于相似度。
 */
object PerceptualHash {

    fun hash(gray8x8: IntArray): Long {
        require(gray8x8.size == 64) { "需要 8x8=64 灰度值" }
        val mean = gray8x8.average()
        var hash = 0L
        for (i in 0 until 64) {
            if (gray8x8[i] >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 归一化距离 [0,1]；0 表示完全相同。 */
    fun distance(a: Long, b: Long): Float = hamming(a, b) / 64f

    /**
     * 从 ARGB 像素数组生成 8x8 灰度（最近邻缩放）。纯函数。
     */
    fun gray8x8(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
        val gray = IntArray(64)
        for (y in 0 until 8) {
            val srcY = (y * height / 8).coerceAtMost(height - 1)
            for (x in 0 until 8) {
                val srcX = (x * width / 8).coerceAtMost(width - 1)
                val pixel = pixels[srcY * width + srcX]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                gray[y * 8 + x] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        return gray
    }

    /**
     * 合成位图（白底）的感知哈希：透明像素按白底合成。
     */
    fun hashOf(pixels: IntArray, width: Int, height: Int): Long =
        hash(gray8x8(pixels, width, height))

    /** 比较两个哈希的相似度 [0,1]，1 表示完全相同。 */
    fun similarity(a: Long, b: Long): Float = 1f - distance(a, b)

    /** 颜色通道差绝对值之和，用于判断「同一张图」。 */
    fun colorDistance(c1: Int, c2: Int): Int {
        val dr = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val dg = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val db = abs((c1 and 0xFF) - (c2 and 0xFF))
        return dr + dg + db
    }
}