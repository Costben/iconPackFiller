package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.generate.GenerationPlan
import dev.artplus.iconpackfiller.generate.TargetSelection
import dev.artplus.iconpackfiller.pack.PackNaming

/**
 * 把一次生成的「目标 + 结果」映射成 [GenerationIconSpec]。
 *
 * 目标来自 [GenerationPlan]（含 activity 与 label，顺序即打包时的 drawable 序号），
 * 结果来自 provider 请求（[GenerationIconOutcome]）。纯逻辑，可 JVM 单测。
 */
object GenerationIconBuilder {

    /**
     * 完整路径：每个计划目标一行。
     *
     * @param plans 本次生成的全部目标（顺序即 `ap_gen_<index>` 的序号）
     * @param acceptedKeys 产出并进入补全包的目标 key（`package/activity`）
     * @param outcomes 该次生成的全部请求结果（含被驳回的）
     */
    fun build(
        plans: List<GenerationPlan>,
        acceptedKeys: Set<String>,
        outcomes: List<GenerationIconOutcome>,
    ): List<GenerationIconSpec> {
        val reasonByPackage = lastReasonByPackage(outcomes)
        return plans.mapIndexed { index, plan ->
            val app = plan.target
            val accepted = TargetSelection.keyOf(app) in acceptedKeys
            GenerationIconSpec(
                packageName = app.packageName,
                activityName = app.activityName,
                label = app.label,
                drawableName = if (accepted) PackNaming.drawableNameFor(index) else null,
                accepted = accepted,
                reason = if (accepted) null else reasonByPackage[app.packageName],
            )
        }
    }

    /**
     * 兜底路径：没有完整计划信息时（取消 / 失败），按请求记录归并出每个目标一行。
     *
     * 缺少 activity 与输出 drawable 名，用 null 表达；只要有一次请求通过即视为 accepted。
     */
    fun fromOutcomes(outcomes: List<GenerationIconOutcome>): List<GenerationIconSpec> =
        outcomes
            .groupBy { it.packageName }
            .map { (packageName, list) ->
                val accepted = list.any { it.accepted }
                val last = list.maxByOrNull { it.attempt }
                GenerationIconSpec(
                    packageName = packageName,
                    activityName = null,
                    label = last?.label,
                    drawableName = null,
                    accepted = accepted,
                    reason = if (accepted) null else last?.reason,
                )
            }
            .sortedBy { it.packageName }

    private fun lastReasonByPackage(outcomes: List<GenerationIconOutcome>): Map<String, String?> =
        outcomes.groupBy { it.packageName }.mapValues { (_, list) ->
            list.maxByOrNull { it.attempt }?.reason
        }
}
