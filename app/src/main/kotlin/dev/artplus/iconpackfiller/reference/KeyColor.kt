package dev.artplus.iconpackfiller.reference

/**
 * 抠像键色（红/绿/蓝三选一）。
 *
 * 不能生成透明图的模型必须把图标画在纯色底上，本端再抠掉。
 * 键色一旦和图标自身颜色撞车，抠图就会把图标一起吃掉，
 * 因此不能写死一种颜色，要按目标图标自适应选择（见 [KeyColorSelector]）。
 */
enum class KeyColor(
    /** 不带 # 的十六进制，用于提示词。 */
    val hex: String,
    val r: Int,
    val g: Int,
    val b: Int,
    /** 英文颜色名，用于提示词。 */
    val displayName: String,
) {
    RED("FF0000", 255, 0, 0, "red"),
    GREEN("00FF00", 0, 255, 0, "green"),
    BLUE("0000FF", 0, 0, 255, "blue");

    companion object {
        /** 无图标可分析时的默认键色（经典绿幕，应用图标里最少见）。 */
        val DEFAULT: KeyColor = GREEN
    }
}

/**
 * 按目标应用原图标自适应选择键色（纯逻辑，可 JVM 单测）。
 *
 * 判定依据是图标**最外圈**的不透明像素：键色底只和图标边缘接触，
 * 边缘处对比越强，洪水填充分离越干净、混色环越窄。
 * 因此对每个候选键色计算"到外圈像素的最小色差"，选最小色差最大的
 * （maximin），即和边缘处处反差最大的颜色。
 *
 * 回退链：外圈不透明像素过少（如 adaptive 图标四角透明）→
 * 用整图不透明像素；整图都没有不透明像素 → [KeyColor.DEFAULT]。
 */
object KeyColorSelector {

    /** 外圈采样带宽度占边长的比例。 */
    const val EDGE_BAND_RATIO = 0.12f

    /** 外圈有效样本下限，低于则视为"外圈无内容"并回退到整图。 */
    const val MIN_RING_SAMPLES = 64

    /** 高于该 alpha 视为不透明像素。 */
    const val OPAQUE_ALPHA = 128

    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        candidates: List<KeyColor> = KeyColor.entries,
    ): KeyColor {
        if (candidates.isEmpty() || width <= 0 || height <= 0 || pixels.size != width * height) {
            return KeyColor.DEFAULT
        }
        val band = maxOf(2, (minOf(width, height) * EDGE_BAND_RATIO).toInt())
        val scores = scoreBand(pixels, width, height, band, candidates)
            ?: scoreAll(pixels, candidates)
            ?: return KeyColor.DEFAULT
        // 取最大最小色差；并列按 candidates 顺序（确定性）。
        var best = 0
        for (i in 1 until candidates.size) {
            if (scores[i] > scores[best]) best = i
        }
        return candidates[best]
    }

    /**
     * @return 每个候选的最小平方色差；有效样本不足时返回 null。
     */
    private fun scoreBand(
        pixels: IntArray,
        width: Int,
        height: Int,
        band: Int,
        candidates: List<KeyColor>,
    ): IntArray? {
        val best = IntArray(candidates.size) { Int.MAX_VALUE }
        var samples = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x >= band && x < width - band && y >= band && y < height - band) continue
                val color = pixels[y * width + x]
                if (((color ushr 24) and 0xFF) < OPAQUE_ALPHA) continue
                samples++
                updateBest(color, candidates, best)
            }
        }
        return if (samples >= MIN_RING_SAMPLES) best else null
    }

    private fun scoreAll(pixels: IntArray, candidates: List<KeyColor>): IntArray? {
        val best = IntArray(candidates.size) { Int.MAX_VALUE }
        var samples = 0
        for (color in pixels) {
            if (((color ushr 24) and 0xFF) < OPAQUE_ALPHA) continue
            samples++
            updateBest(color, candidates, best)
        }
        return if (samples > 0) best else null
    }

    private fun updateBest(color: Int, candidates: List<KeyColor>, best: IntArray) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        for (i in candidates.indices) {
            val key = candidates[i]
            val dr = r - key.r
            val dg = g - key.g
            val db = b - key.b
            val d = dr * dr + dg * dg + db * db
            if (d < best[i]) best[i] = d
        }
    }
}
