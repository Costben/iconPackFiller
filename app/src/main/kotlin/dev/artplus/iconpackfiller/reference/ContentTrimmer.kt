package dev.artplus.iconpackfiller.reference

/**
 * 图标内容裁边（纯逻辑，可 JVM 单测）。
 *
 * ContactSheet 里每张图按内容铺满格子：图标位图四周常有透明边或纯色留白
 * （实测 Aura 包内图标大约每边 4-6%），不裁掉的话同一对比组里
 * 右侧风格图看起来比左侧原图小一圈，模型会把「缩小+留白」误当成画风的一环。
 *
 * 两条判定路径：
 * 1. 四角存在透明（adaptive/掩码图标、带透明留白的包内图标）→ 按 alpha 取内容盒；
 * 2. 四角不透明且同色（满幅纯色图标）→ 按颜色取内容盒，但限制每边最多裁 [MAX_OPAQUE_TRIM_RATIO]，
 *    防止把「满幅纯色底 + 中央小图形」误裁成只剩中央图形。
 */
object ContentTrimmer {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 低于该 alpha 视为透明（8/255 约等于像素完全透明，保留极淡阴影边缘）。 */
    const val DEFAULT_ALPHA_THRESHOLD = 8

    /** 「角像素是不透明」的 alpha 门槛。 */
    const val OPAQUE_ALPHA = 200

    /** 与角落采样色的最大色差（RGB 曼哈顿距离）。 */
    const val DEFAULT_TOLERANCE = 24

    /** 四角不透明同色时，每边最多允许裁掉的比例。 */
    const val MAX_OPAQUE_TRIM_RATIO = 0.15f

    /** 内容盒最小边长；比这更小视为异常图，不裁。 */
    const val MIN_CONTENT = 8

    /**
     * 计算内容包围盒（左闭右开像素坐标）。
     *
     * @return null 表示无需/不宜裁边（整图是内容、整图是背景、或留白超出安全上限）
     */
    fun contentBox(
        pixels: IntArray,
        width: Int,
        height: Int,
        alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
        tolerance: Int = DEFAULT_TOLERANCE,
    ): Box? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        val corners = intArrayOf(
            pixels[0],
            pixels[width - 1],
            pixels[(height - 1) * width],
            pixels[height * width - 1],
        )
        val hasTransparentCorner = corners.any { alphaOf(it) < OPAQUE_ALPHA }

        val box = if (hasTransparentCorner) {
            boundingBox(pixels, width, height) { pixel -> alphaOf(pixel) >= alphaThreshold }
        } else {
            // 四角全不透明：只有四角同色才敢按颜色裁；否则整图当内容
            if (!allSimilar(corners, tolerance)) return null
            val background = average(corners)
            boundingBox(pixels, width, height) { pixel -> !closeTo(pixel, background, tolerance) }
        } ?: return null

        return box.normalized(
            width = width,
            height = height,
            maxTrimRatio = if (hasTransparentCorner) null else MAX_OPAQUE_TRIM_RATIO,
        )
    }

    private fun boundingBox(
        pixels: IntArray,
        width: Int,
        height: Int,
        isContent: (Int) -> Boolean,
    ): Box? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!isContent(pixels[row + x])) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        return Box(left, top, right + 1, bottom + 1)
    }

    /**
     * 裁量安全检查：
     * - 两轴都没裁到 2px 以上 → 不值得裁
     * - 每边裁掉超过 [maxTrimRatio] → 视为把设计底色误判成背景，放弃
     * - 内容过小 → 放弃
     */
    private fun Box.normalized(width: Int, height: Int, maxTrimRatio: Float?): Box? {
        if (right <= left || bottom <= top) return null
        val trimX = width - this.width
        val trimY = height - this.height
        if (trimX < 2 && trimY < 2) return null
        if (maxTrimRatio != null) {
            val maxX = (width * maxTrimRatio).toInt()
            val maxY = (height * maxTrimRatio).toInt()
            if (left > maxX || width - right > maxX) return null
            if (top > maxY || height - bottom > maxY) return null
        }
        if (this.width < MIN_CONTENT || this.height < MIN_CONTENT) return null
        return this
    }

    private fun allSimilar(colors: IntArray, tolerance: Int): Boolean {
        val first = colors.first()
        for (i in 1 until colors.size) {
            if (distance(colors[i], first) > tolerance) return false
        }
        return true
    }

    private fun average(colors: IntArray): Int {
        var r = 0
        var g = 0
        var b = 0
        for (color in colors) {
            r += redOf(color)
            g += greenOf(color)
            b += blueOf(color)
        }
        val n = colors.size
        return argb(255, r / n, g / n, b / n)
    }

    private fun closeTo(pixel: Int, reference: Int, tolerance: Int): Boolean =
        distance(pixel, reference) <= tolerance

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(redOf(a) - redOf(b)) +
            kotlin.math.abs(greenOf(a) - greenOf(b)) +
            kotlin.math.abs(blueOf(a) - blueOf(b))

    private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

    private fun redOf(color: Int): Int = (color ushr 16) and 0xFF

    private fun greenOf(color: Int): Int = (color ushr 8) and 0xFF

    private fun blueOf(color: Int): Int = color and 0xFF

    private fun argb(alpha: Int, r: Int, g: Int, b: Int): Int =
        (alpha shl 24) or (r shl 16) or (g shl 8) or b
}
