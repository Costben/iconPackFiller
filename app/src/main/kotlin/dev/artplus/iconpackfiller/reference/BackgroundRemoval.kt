package dev.artplus.iconpackfiller.reference

/**
 * 背景移除（纯逻辑，无 android.graphics 依赖，可在 JVM 单测）。
 *
 * 生成模型只会返回不透明 RGB，无法输出真 alpha：
 * 即使请求 `background=transparent`，模型也常常**画出**一块棋盘格来"表示"透明，
 * 或铺一层纯色底。因此透明化必须在本端做。
 *
 * 策略：两遍从边框向内的洪水填充 + 一遍边缘去键色。
 *
 * 第一遍只删「与边框同色」的连通区域（容差 [tolerance]），
 * 因此图标主体（不与边框连通）绝不会被误删。
 *
 * 第二遍清理模型画在图标下的软阴影与底色混色边：
 * 阴影是「低饱和 + 从底色向内严格变暗的渐变」，沿该梯度跟随吃掉；
 * 一旦亮度出现平台（连续 [fringePlateauSteps] 步不再变暗）就停，
 * 因此大面积浅色平涂（如米色图标底色）最多被侵蚀几像素，
 * 而饱和的图标色（[fringeSaturation] 以上）会直接截断扩散。
 *
 * 第三遍去键色：高对比键色底（如品红）会染进抗锯齿边缘（饱和色，
 * 第二遍拦不住），对贴边一圈像素按含键量还原部分透明度并扣除染色。
 * 只影响贴边 1 像素层，不向内传播。
 *
 * 支持两种真实底色形态：
 * - 纯色底（白/品红键色等）
 * - 双色棋盘格（模型画的"透明示意"）
 */
object BackgroundRemoval {

    /** 边框取样的环宽（像素）。 */
    private const val RING = 3

    /** 通道量化步长（用于底色聚类）。 */
    private const val QUANT = 16

    /**
     * 梯度跟随的"变暗"判定步长：子像素亮度比父像素低超过该值才算严格变暗、
     * 重置平台计数；以内视为噪声/持平。模型 PNG 干净，2 足够滤掉压缩抖动。
     */
    private const val LUMINANCE_STEP = 2

    data class Result(
        /** 处理后的 ARGB 像素；未生效时为原样。 */
        val pixels: IntArray,
        /** 被判定为背景的像素占比。 */
        val removedRatio: Float,
        /** 是否实际生效（未通过安全护栏时为 false）。 */
        val applied: Boolean,
        val reason: String = "",
    )

    /**
     * @param pixels ARGB_8888 像素
     * @param tolerance 单通道容差（0-255）
     * @param fringeTolerance 第二遍（阴影/混色边）单通道容差上限，必须大于 [tolerance]
     * @param fringeSaturation 第二遍只吃低于此饱和度的像素（0-1），饱和图标色直接截断扩散
     * @param fringePlateauSteps 第二遍沿梯度跟随时，允许亮度"不再变暗"的连续步数；
     *   超过即停，用于保住大面积浅色平涂
     * @param despillFull 第三遍（去键色）的色差上限：与底色差超过该值的像素视为
     *   实色图标，不动；区间 (tolerance, despillFull) 内的边缘像素按含键量
     *   还原部分透明度并扣除底色染色。必须大于 [tolerance]，否则跳过第三遍
     * @param minRemoved 生效下限（低于视为"没有背景"，不处理）
     * @param maxRemoved 生效上限；只用于拦住"整图都是底色"这类退化输入
     *   （真正的安全护栏是洪水填充本身：非底色像素会阻断扩散，
     *   因此小主体占大留白的情形必须允许移除率很高）
     */
    fun removeBorderConnectedBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        tolerance: Int = 30,
        fringeTolerance: Int = 110,
        fringeSaturation: Float = 0.30f,
        fringePlateauSteps: Int = 3,
        despillFull: Int = 90,
        minRemoved: Float = 0.01f,
        maxRemoved: Float = 0.995f,
    ): Result {
        require(pixels.size == width * height) { "像素数量与尺寸不符" }
        if (width < 4 || height < 4) return Result(pixels, 0f, false, "尺寸过小")

        val tones = borderTones(pixels, width, height)
        if (tones.isEmpty()) return Result(pixels, 0f, false, "边框无稳定底色")

        val isBackground = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0

        fun enqueue(index: Int) {
            if (!isBackground[index] && matches(pixels[index], tones, tolerance)) {
                isBackground[index] = true
                queue[tail++] = index
            }
        }

        // 种子：整圈边框
        for (x in 0 until width) {
            enqueue(x)
            enqueue((height - 1) * width + x)
        }
        for (y in 0 until height) {
            enqueue(y * width)
            enqueue(y * width + width - 1)
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) enqueue(index - 1)
            if (x < width - 1) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y < height - 1) enqueue(index + width)
        }

        val firstPassRemoved = countTrue(isBackground)
        if (fringeTolerance > tolerance) {
            removeFringe(
                pixels = pixels,
                width = width,
                height = height,
                tones = tones,
                tolerance = tolerance,
                fringeTolerance = fringeTolerance,
                fringeSaturation = fringeSaturation,
                fringePlateauSteps = fringePlateauSteps,
                isBackground = isBackground,
                queue = queue,
            )
        }

        val out = pixels.copyOf()
        for (index in out.indices) {
            if (isBackground[index]) out[index] = out[index] and 0x00FFFFFF
        }
        if (despillFull > tolerance) {
            despill(
                pixels = pixels,
                width = width,
                height = height,
                tones = tones,
                tolerance = tolerance,
                despillFull = despillFull,
                isBackground = isBackground,
                out = out,
            )
        }

        val removed = countTrue(isBackground)
        val ratio = removed.toFloat() / pixels.size

        if (ratio < minRemoved) return Result(pixels, ratio, false, "未检出背景（占比过低）")
        if (ratio > maxRemoved) return Result(pixels, ratio, false, "整图均为同色（疑似模型返回空白）")

        val fringeRemoved = removed - firstPassRemoved
        val reason = if (fringeRemoved > 0) {
            "已移除背景 ${(ratio * 100).toInt()}%（含阴影/边缘 ${(fringeRemoved * 100f / pixels.size).toInt()}%）"
        } else {
            "已移除背景 ${(ratio * 100).toInt()}%"
        }
        return Result(out, ratio, true, reason)
    }

    /**
     * 第二遍：从第一遍已移除区域出发，吃掉连通的阴影/混色像素。
     *
     * 每个候选像素必须同时满足：
     * 1. 与底色的单通道色差在 (tolerance, fringeTolerance] 区间
     *    （区间下限把"纯白图标芯"挡在外面：它与底色差 ≤ tolerance，
     *    第一遍没连通吃掉，第二遍也不进入）；
     * 2. 低饱和（图标色截断扩散）；
     * 3. 沿扩散路径亮度持续变暗，平台步数 ≤ [fringePlateauSteps]
     *    （浅色平涂是平的，走几步就停；阴影是渐变，能一直跟到底）。
     */
    private fun removeFringe(
        pixels: IntArray,
        width: Int,
        height: Int,
        tones: List<Int>,
        tolerance: Int,
        fringeTolerance: Int,
        fringeSaturation: Float,
        fringePlateauSteps: Int,
        isBackground: BooleanArray,
        queue: IntArray,
    ) {
        var head = 0
        var tail = 0
        // 种子：第一遍已移除的全部像素（亮度取自身像素值，白底种子亮度最高）
        for (index in isBackground.indices) {
            if (isBackground[index]) queue[tail++] = index
        }
        val plateau = ByteArray(pixels.size)

        fun tryEat(parent: Int, index: Int) {
            if (isBackground[index]) return
            val color = pixels[index]
            if (((color ushr 24) and 0xFF) == 0) return
            val distance = distanceToTones(color, tones)
            if (distance <= tolerance || distance > fringeTolerance) return
            if (saturationOf(color) > fringeSaturation) return
            val parentLum = luminanceOf(pixels[parent])
            val lum = luminanceOf(color)
            // 亮度不再变暗（持平或变亮，容忍 2 级噪声）则平台步数 +1
            val next = if (lum >= parentLum - LUMINANCE_STEP) plateau[parent] + 1 else 0
            if (next > fringePlateauSteps) return
            isBackground[index] = true
            plateau[index] = next.toByte()
            queue[tail++] = index
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) tryEat(index, index - 1)
            if (x < width - 1) tryEat(index, index + 1)
            if (y > 0) tryEat(index, index - width)
            if (y < height - 1) tryEat(index, index + width)
        }
    }

    /**
     * 第三遍：去键色（despill），只处理与已移除区相邻的一圈边缘像素。
     *
     * 高对比键色底（如品红）会把颜色染进图标的抗锯齿边缘：混色像素是饱和的，
     * 第二遍的饱和门拦不住。设观察值 C = a·F + (1-a)·B（B 为最近的底色），
     * 用 min-channel 估计含键量 keyAmt（键色强通道上的最小比值），
     * 则 a = 1 - keyAmt，F = (C - keyAmt·B) / a。
     * 对真混色可精确还原主体色；对实色图标（色差 ≥ [despillFull]）直接跳过，
     * 因此最多只影响贴边的 1 像素层，不会向内传播。
     */
    private fun despill(
        pixels: IntArray,
        width: Int,
        height: Int,
        tones: List<Int>,
        tolerance: Int,
        despillFull: Int,
        isBackground: BooleanArray,
        out: IntArray,
    ) {
        for (index in pixels.indices) {
            if (isBackground[index]) continue
            val color = pixels[index]
            if (((color ushr 24) and 0xFF) == 0) continue
            val x = index % width
            val y = index / width
            val adjacent = (x > 0 && isBackground[index - 1]) ||
                (x < width - 1 && isBackground[index + 1]) ||
                (y > 0 && isBackground[index - width]) ||
                (y < height - 1 && isBackground[index + width])
            if (!adjacent) continue
            val distance = distanceToTones(color, tones)
            if (distance <= tolerance || distance >= despillFull) continue
            val b = dequantizeTone(nearestTone(color, tones))
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            var keyAmt = 1f
            var hasKeyChannel = false
            if (br > 0) { keyAmt = minOf(keyAmt, cr.toFloat() / br); hasKeyChannel = true }
            if (bg > 0) { keyAmt = minOf(keyAmt, cg.toFloat() / bg); hasKeyChannel = true }
            if (bb > 0) { keyAmt = minOf(keyAmt, cb.toFloat() / bb); hasKeyChannel = true }
            // 纯黑底没有键通道，无法估计含键量，跳过。
            if (!hasKeyChannel) continue
            keyAmt = keyAmt.coerceIn(0f, 1f)
            val alpha = 1f - keyAmt
            if (alpha <= 0.02f) {
                isBackground[index] = true
                out[index] = out[index] and 0x00FFFFFF
                continue
            }
            if (alpha >= 0.999f) continue
            val fr = (((cr - keyAmt * br) / alpha).toInt()).coerceIn(0, 255)
            val fg = (((cg - keyAmt * bg) / alpha).toInt()).coerceIn(0, 255)
            val fb = (((cb - keyAmt * bb) / alpha).toInt()).coerceIn(0, 255)
            out[index] = ((alpha * 255).toInt() shl 24) or (fr shl 16) or (fg shl 8) or fb
        }
    }

    /**
     * 边框底色聚类：取占比最高的 1-2 个色调（覆盖纯色底与棋盘格）。
     */
    private fun borderTones(pixels: IntArray, width: Int, height: Int): List<Int> {
        val counts = HashMap<Int, Int>()
        fun sample(x: Int, y: Int) {
            val key = quantize(pixels[y * width + x])
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (d in 0 until RING) {
            for (x in 0 until width) {
                sample(x, d.coerceAtMost(height - 1))
                sample(x, (height - 1 - d).coerceAtLeast(0))
            }
            for (y in 0 until height) {
                sample(d.coerceAtMost(width - 1), y)
                sample((width - 1 - d).coerceAtLeast(0), y)
            }
        }
        val total = counts.values.sum()
        if (total == 0) return emptyList()
        // 取占比 >= 20% 的色调，最多两个：纯色底命中 1 个，棋盘格命中 2 个。
        // 阈值不能再低，否则会把主体边缘色也当成背景。
        return counts.entries
            .sortedByDescending { it.value }
            .filter { it.value.toFloat() / total >= 0.20f }
            .take(2)
            .map { it.key }
    }

    private fun quantize(color: Int): Int {
        val r = ((color shr 16) and 0xFF) / QUANT
        val g = ((color shr 8) and 0xFF) / QUANT
        val b = (color and 0xFF) / QUANT
        return (r shl 16) or (g shl 8) or b
    }

    private fun matches(color: Int, tones: List<Int>, tolerance: Int): Boolean {
        // 已经透明的像素不算背景（无需再次移除）
        if (((color ushr 24) and 0xFF) == 0) return false
        return distanceToTones(color, tones) <= tolerance
    }

    /** 与底色的最小单通道色差（对多底色取最小）。 */
    private fun distanceToTones(color: Int, tones: List<Int>): Int {
        var best = Int.MAX_VALUE
        for (tone in tones) {
            val d = distanceToTone(color, tone)
            if (d < best) best = d
        }
        return best
    }

    /** 与单个底色的单通道色差。 */
    private fun distanceToTone(color: Int, tone: Int): Int {
        val dr = kotlin.math.abs(((color shr 16) and 0xFF) - ((tone shr 16) and 0xFF) * QUANT)
        val dg = kotlin.math.abs(((color shr 8) and 0xFF) - ((tone shr 8) and 0xFF) * QUANT)
        val db = kotlin.math.abs((color and 0xFF) - (tone and 0xFF) * QUANT)
        return maxOf(dr, dg, db)
    }

    /** 距离最近的底色（量化值）。 */
    private fun nearestTone(color: Int, tones: List<Int>): Int {
        var bestTone = tones[0]
        var best = Int.MAX_VALUE
        for (tone in tones) {
            val d = distanceToTone(color, tone)
            if (d < best) {
                best = d
                bestTone = tone
            }
        }
        return bestTone
    }

    /** 量化底色还原为 RGB（取区间中值；返回 0xRRGGBB）。 */
    private fun dequantizeTone(tone: Int): Int {
        val r = minOf(((tone shr 16) and 0xFF) * QUANT + QUANT / 2, 255)
        val g = minOf(((tone shr 8) and 0xFF) * QUANT + QUANT / 2, 255)
        val b = minOf((tone and 0xFF) * QUANT + QUANT / 2, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun saturationOf(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val mx = maxOf(r, g, b)
        if (mx == 0) return 0f
        return (mx - minOf(r, g, b)).toFloat() / mx
    }

    private fun luminanceOf(color: Int): Int =
        (((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF))

    private fun countTrue(flags: BooleanArray): Int {
        var n = 0
        for (flag in flags) if (flag) n++
        return n
    }
}
