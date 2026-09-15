package dev.artplus.iconpackfiller.reference

import dev.artplus.iconpackfiller.pack.IconStats

/**
 * 参考对候选：本机某应用的原始图标 ↔ 图标包内同应用的重绘版。
 */
data class ReferencePair(
    val packageName: String,
    val label: String?,
    val category: Int?,
    val originalStats: IconStats,
    val packStats: IconStats,
    /**
     * 该应用在图标包内命中的 drawable 名。
     *
     * 必须由覆盖率扫描阶段带出：许多图标包只写 `ComponentInfo{pkg/activity}` 精确条目，
     * 仅凭包名无法重新命中（[dev.artplus.iconpackfiller.pack.AppFilterDocument.match] 的
     * 包级兜底索引会漏掉它们）。
     */
    val packDrawableName: String? = null,
    /** 原图加载用的 activity 名（精确条目命中场景需要，避免加载到错误的 activity 图标）。 */
    val activityName: String? = null,
) {
    /** 分类匹配分。 */
    fun categoryScore(target: ReferencePair, sameCategoryBoost: Int): Int =
        if (target.category != null && category == target.category) sameCategoryBoost else 0

    /** 主色距离（欧氏平方，越小越近）。 */
    fun colorDistance(target: ReferencePair): Int {
        val a = originalStats.dominantColor
        val b = target.originalStats.dominantColor
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }
}

/**
 * 参考对选择器。
 *
 * 优先级：同分类 > 主色相近 > 随机。同一参考应用不重复使用。
 *
 * 纯逻辑（输入统计信息，不接触 Bitmap），可 JVM 单测。
 */
object ReferencePairSelector {

    data class Config(
        val pairCount: Int = 2,
        val sameCategoryBoost: Int = 1_000_000,
        /** 主色距离权重（每单位扣分）。 */
        val colorWeight: Int = 1,
        /** 随机兜底种子；null 用时间。 */
        val randomSeed: Long? = null,
    )

    /**
     * @param candidates 可用于参考的候选（已包含原图与包内图的统计）
     * @param target 目标应用（未适配）
     * @return 选中的 1..pairCount 个候选，按得分降序
     */
    fun select(
        candidates: List<ReferencePair>,
        target: ReferencePair,
        config: Config = Config(),
    ): List<ReferencePair> {
        if (candidates.isEmpty()) return emptyList()
        val random = config.randomSeed?.let { java.util.Random(it) } ?: java.util.Random()
        val scored = candidates
            .filter { it.packageName != target.packageName }
            .map { it to score(it, target, config) }
            .sortedWith(
                compareByDescending<Pair<ReferencePair, Int>> { it.second }
                    .thenBy { it.first.packageName },
            )

        val picked = ArrayList<ReferencePair>(config.pairCount)
        val used = HashSet<String>()
        for ((candidate, _) in scored) {
            if (picked.size >= config.pairCount) break
            if (used.add(candidate.packageName)) picked.add(candidate)
        }
        // 若去重后不足且仍有候选，随机补足（仍不重复）
        if (picked.size < config.pairCount) {
            val remaining = candidates
                .filter { it.packageName != target.packageName && it.packageName !in used }
                .shuffled(random)
            for (candidate in remaining) {
                if (picked.size >= config.pairCount) break
                picked.add(candidate)
            }
        }
        return picked
    }

    private fun score(
        candidate: ReferencePair,
        target: ReferencePair,
        config: Config,
    ): Int {
        val category = candidate.categoryScore(target, config.sameCategoryBoost)
        val color = candidate.colorDistance(target)
        return category - color * config.colorWeight
    }
}