package dev.artplus.iconpackfiller.reference

import dev.artplus.iconpackfiller.pack.IconStats

/**
 * 生成输出校验。
 *
 * 规则（M4 §4）：
 * - 正方形
 * - 边长 >= 512
 * - 非空白（不透明像素占比 > 阈值）
 * - 与源图感知哈希距离低于阈值（保品牌识别度）
 * - alpha 与包内多数图标一致（由调用方传入包内典型 alphaRatio）
 *
 * [Config.maxHashDistance] 的取值依据：实测 Aura 图标包内**作者本人的重绘**
 * 与源图的感知距离为 min 0.08 / 中位 0.25 / max 0.47。阈值必须高于这个分布，
 * 否则会把专业水准的风格化判为「丢品牌识别度」。
 */
object OutputValidator {

    data class Config(
        val minSize: Int = 512,
        val requireSquare: Boolean = true,
        val minOpaqueRatio: Float = 0.02f,
        /**
         * 与源图感知距离上限。
         *
         * 0.60 来自真实分布标定（作者重绘 max 0.47，留出余量）。原值 0.45 会误杀正常重绘。
         */
        val maxHashDistance: Float = 0.60f,
        val alphaTolerance: Float = 0.35f,
    )

    sealed class Result {
        data object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * @param generatedStats 生成图的统计
     * @param generatedHash 生成图感知哈希
     * @param sourceHash 目标原图感知哈希
     * @param packTypicalAlpha 包内多数图标的 alphaRatio（透明占比）
     */
    fun validate(
        generatedStats: IconStats,
        generatedHash: Long,
        sourceHash: Long,
        packTypicalAlpha: Float? = null,
        config: Config = Config(),
    ): Result {
        if (config.requireSquare && !generatedStats.isSquare) {
            return Result.Invalid("输出不是正方形（${generatedStats.width}x${generatedStats.height}）")
        }
        if (generatedStats.width < config.minSize || generatedStats.height < config.minSize) {
            return Result.Invalid("输出边长不足 ${config.minSize}px（${generatedStats.width}）")
        }
        val opaqueRatio = 1f - generatedStats.alphaRatio
        if (opaqueRatio < config.minOpaqueRatio) {
            return Result.Invalid("输出近乎空白（不透明占比 ${"%.1f%%".format(opaqueRatio * 100)}）")
        }
        if (generatedStats.isBlank) {
            return Result.Invalid("输出为纯色或全透明")
        }
        val hashDistance = PerceptualHash.distance(generatedHash, sourceHash)
        if (hashDistance > config.maxHashDistance) {
            return Result.Invalid("与源图差异过大（感知距离 ${"%.2f".format(hashDistance)}），可能丢失品牌识别度")
        }
        if (packTypicalAlpha != null) {
            val delta = kotlin.math.abs(generatedStats.alphaRatio - packTypicalAlpha)
            if (delta > config.alphaTolerance) {
                return Result.Invalid("alpha 与包内多数图标不一致（差 ${"%.2f".format(delta)}）")
            }
        }
        return Result.Valid
    }
}