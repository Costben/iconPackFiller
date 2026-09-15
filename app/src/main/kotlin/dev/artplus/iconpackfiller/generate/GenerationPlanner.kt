package dev.artplus.iconpackfiller.generate

import dev.artplus.iconpackfiller.coverage.LaunchableApp
import dev.artplus.iconpackfiller.reference.ReferencePair

/**
 * 生成计划：目标应用 + 选中的参考对。纯数据，可 JVM 单测。
 */
data class GenerationPlan(
    val target: LaunchableApp,
    val references: List<ReferencePair>,
    /** 尝试序号，0 为首次；重试时递增，用于选择不同参考。 */
    val attempt: Int = 0,
    /**
     * 参考是否为「确认范围」页固定下来的（重新抽样 / 选择应用）。
     *
     * 固定参考在重试时保持不换——换参考重试只对网络类错误有意义，
     * 而用户手动敲定的参考不应该被自动替换。
     */
    val fixedReferences: Boolean = false,
)

/**
 * 把覆盖率报告的 unmatched 列表 + 参考候选池转换成生成计划列表。
 *
 * 重试策略：首次用 [ReferencePairSelector] 的主选；重试时把已用过的参考排除后重选。
 * 纯逻辑，JVM 可测。
 */
object GenerationPlanner {

    /**
     * @param targets 本次要生成的应用（已由 [TargetSelection] 过滤）
     */
    fun plan(
        targets: List<LaunchableApp>,
        referencePool: List<ReferencePair>,
        targetPairs: Map<String, ReferencePair>,
        config: ReferencePairSelectorConfig = ReferencePairSelectorConfig(),
    ): List<GenerationPlan> {
        val plans = ArrayList<GenerationPlan>(targets.size)
        for (app in targets) {
            val target = targetPairs[app.packageName] ?: continue
            val refs = dev.artplus.iconpackfiller.reference.ReferencePairSelector.select(
                candidates = referencePool,
                target = target,
                config = dev.artplus.iconpackfiller.reference.ReferencePairSelector.Config(
                    pairCount = config.pairCount,
                    randomSeed = config.randomSeed,
                ),
            )
            plans.add(GenerationPlan(target = app, references = refs, attempt = 0))
        }
        return plans
    }

    /**
     * 用「确认范围」页固定下来的参考对为每个目标生成计划。
     *
     * 参考对是整批共用的（用户抽样或手选的结果），只按目标自身做一次自引用排除；
     * 若排除后为空（例如只选了目标自己）则保留原样，避免无参考可用。
     */
    fun planFixed(
        targets: List<LaunchableApp>,
        targetPairs: Map<String, ReferencePair>,
        references: List<ReferencePair>,
    ): List<GenerationPlan> {
        if (references.isEmpty()) return emptyList()
        val plans = ArrayList<GenerationPlan>(targets.size)
        for (app in targets) {
            if (targetPairs[app.packageName] == null) continue
            val refs = references
                .filterNot { it.packageName == app.packageName }
                .ifEmpty { references }
            plans.add(
                GenerationPlan(
                    target = app,
                    references = refs,
                    attempt = 0,
                    fixedReferences = true,
                ),
            )
        }
        return plans
    }

    /**
     * 生成重试计划：排除上次使用的参考包，重新选择。
     *
     * 固定参考（[GenerationPlan.fixedReferences]）只递增尝试序号，不换参考。
     */
    fun replan(
        plan: GenerationPlan,
        targetPair: ReferencePair,
        referencePool: List<ReferencePair>,
        config: ReferencePairSelectorConfig = ReferencePairSelectorConfig(),
    ): GenerationPlan {
        if (plan.fixedReferences) return plan.copy(attempt = plan.attempt + 1)
        val usedPackages = plan.references.map { it.packageName }.toSet()
        val remaining = referencePool.filterNot { it.packageName in usedPackages }
        val pool = remaining.ifEmpty { referencePool }
        val refs = dev.artplus.iconpackfiller.reference.ReferencePairSelector.select(
            candidates = pool,
            target = targetPair,
            config = dev.artplus.iconpackfiller.reference.ReferencePairSelector.Config(
                pairCount = config.pairCount,
                randomSeed = (config.randomSeed ?: 0L) + plan.attempt + 1,
            ),
        )
        return plan.copy(references = refs, attempt = plan.attempt + 1)
    }
}

data class ReferencePairSelectorConfig(
    val pairCount: Int = 2,
    val randomSeed: Long? = null,
)