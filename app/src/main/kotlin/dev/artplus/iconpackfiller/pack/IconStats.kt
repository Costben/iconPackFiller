package dev.artplus.iconpackfiller.pack

/**
 * 图标位图统计。用于风格统计与参考对选择（M4）。
 * 纯函数实现（输入 ARGB 像素数组），可在 JVM 单测中直接验证。
 */
data class IconStats(
    val width: Int,
    val height: Int,
    /** 透明/半透明像素占比 [0,1]。 */
    val alphaRatio: Float,
    /** 主色（不透明像素直方图峰值，ARGB）。全透明时返回 0。 */
    val dominantColor: Int,
    /** 全部像素近乎透明或全同色。 */
    val isBlank: Boolean,
) {
    val isSquare: Boolean get() = width == height

    companion object {
        private const val ALPHA_THRESHOLD = 250

        fun fromPixels(pixels: IntArray, width: Int, height: Int): IconStats {
            require(width > 0 && height > 0)
            require(pixels.size == width * height)

            var transparent = 0
            val histogram = HashMap<Int, LongArray>(64)
            var firstColor = 0
            var firstSet = false
            var uniform = true

            for (pixel in pixels) {
                val alpha = pixel ushr 24
                if (alpha < ALPHA_THRESHOLD) {
                    transparent++
                    continue
                }
                val quantized = quantize(pixel)
                val bucket = histogram.getOrPut(quantized) { LongArray(4) }
                bucket[0]++
                bucket[1] += (pixel shr 16) and 0xFF
                bucket[2] += (pixel shr 8) and 0xFF
                bucket[3] += pixel and 0xFF

                if (!firstSet) {
                    firstColor = pixel
                    firstSet = true
                } else if (uniform && pixel != firstColor) {
                    uniform = false
                }
            }

            val opaque = pixels.size - transparent
            val dominant = if (opaque == 0) {
                0
            } else {
                val peak = histogram.maxByOrNull { it.value[0] }!!.value
                val count = peak[0]
                val r = (peak[1] / count).toInt()
                val g = (peak[2] / count).toInt()
                val b = (peak[3] / count).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            val blank = opaque == 0 || (uniform && opaque > 0)

            return IconStats(
                width = width,
                height = height,
                alphaRatio = transparent.toFloat() / pixels.size,
                dominantColor = dominant,
                isBlank = blank,
            )
        }

        /** 每通道保留 4 bit，直方图桶键。 */
        private fun quantize(pixel: Int): Int {
            val r = (pixel shr 20) and 0xF
            val g = (pixel shr 12) and 0xF
            val b = (pixel shr 4) and 0xF
            return (r shl 8) or (g shl 4) or b
        }
    }
}