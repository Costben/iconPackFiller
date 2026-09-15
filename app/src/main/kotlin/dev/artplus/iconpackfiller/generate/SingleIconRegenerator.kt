package dev.artplus.iconpackfiller.generate

import android.content.Context
import dev.artplus.iconpackfiller.coverage.AppScanner
import dev.artplus.iconpackfiller.coverage.CoverageCalculator
import dev.artplus.iconpackfiller.coverage.CoverageRules
import dev.artplus.iconpackfiller.coverage.LaunchableApp
import dev.artplus.iconpackfiller.pack.DrawableDirectoryDetector
import dev.artplus.iconpackfiller.provider.ImageProvider
import dev.artplus.iconpackfiller.reference.ReferencePairSelector

/**
 * 单图标重新请求：批次详情页「重新取样 / 重新生成」的执行器。
 *
 * - [Mode.KeepOriginal]：按记录的原参考重发请求（可换模型）；
 * - [Mode.Resample]：从图标包重新取样参考对后再请求（模型沿用原请求）。
 *
 * 校验不通过的请求也会以 [GenerationAttempt] 返回（可在对比列表里看到原因）；
 * 网络等可重试错误按 [maxAttempts] 重试，最终失败则抛 [GenerationException]。
 */
class SingleIconRegenerator(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) {

    sealed interface Mode {
        /** 保持原参考；[references] 来自批次记录的快照。 */
        data class KeepOriginal(val references: List<ReferenceSnapshot>) : Mode

        /**
         * 重新取样 [count] 组参考；[exclude] 为此前该目标用过的包名
         * （选择器评分是确定性的，不排除的话每次都会选回同样的参考）。
         */
        data class Resample(val count: Int, val exclude: Set<String> = emptySet()) : Mode
    }

    suspend fun regenerate(
        pack: IconPackSource,
        target: LaunchableApp,
        mode: Mode,
        provider: ImageProvider,
        provenance: GenerationProvenance,
        rules: CoverageRules,
        maxAttempts: Int,
        callLimit: Int?,
        /** 当前模型的透明直出是否生效（调用方 resolve 好传入）。 */
        requestTransparentBackground: Boolean = false,
    ): GenerationAttempt {
        val targetIcon = AppIconLoader.load(context, target.packageName, target.activityName)
            ?: error("目标图标加载失败：${target.packageName}")
        try {
            val targetPair = ReferencePoolBuilder.loadTargetPair(context, target)
                ?: error("目标统计构建失败：${target.packageName}")

            val (pool, plan) = when (mode) {
                is Mode.KeepOriginal -> {
                    val restored = mode.references.mapNotNull { snapshot ->
                        ReferencePoolBuilder.pairFromSnapshot(context, pack, snapshot, onDiagnostic)
                    }
                    if (restored.isEmpty()) error("原参考已不可用（应用可能已卸载）")
                    restored to GenerationPlan(target = target, references = restored, attempt = 0)
                }
                is Mode.Resample -> {
                    val apps = AppScanner(context).scan()
                    val report = CoverageCalculator.compute(
                        apps = apps,
                        appFilter = pack.document,
                        availableDrawables = pack.availableDrawables,
                        rules = rules,
                    )
                    val pool = ReferencePoolBuilder.build(context, pack, report, onDiagnostic)
                    // 排除用过的参考，避免确定性评分每次选回同一批
                    val remaining = pool.filterNot { it.packageName in mode.exclude }
                    val picked = ReferencePairSelector.select(
                        candidates = remaining.ifEmpty { pool },
                        target = targetPair,
                        config = ReferencePairSelector.Config(pairCount = mode.count),
                    )
                    if (picked.isEmpty()) error("参考池为空，无法重新取样")
                    pool to GenerationPlan(target = target, references = picked, attempt = 0)
                }
            }

            // 生成模型只给 1024/1254 大图；缩到原包图标尺寸，与整包运行一致
            val convention = DrawableDirectoryDetector.detect(pack.sourceApk)
            var lastAttempt: GenerationAttempt? = null
            val pipeline = IconGenerationPipeline(
                provider = provider,
                maxAttempts = maxAttempts,
                budget = GenerationBudget(callLimit),
                outputSize = convention.pixelSize,
                requestTransparentBackground = requestTransparentBackground,
                provenance = provenance,
                onAttempt = { lastAttempt = it },
            )
            try {
                val icon = pipeline.generate(
                    targetIcon = targetIcon,
                    targetPair = targetPair,
                    initialPlan = plan,
                    referencePool = pool,
                    loadReference = { reference ->
                        ReferencePoolBuilder.loadReferenceBitmaps(context, pack, reference, onDiagnostic)
                    },
                )
                // 只需要 PNG 字节（进对比记录）；位图回收，避免大图常驻
                icon.bitmap.recycle()
            } catch (e: GenerationException) {
                // 校验不通过：attempt 已通过 onAttempt 拿到，返回给界面展示原因
                lastAttempt?.let { return it }
                throw e
            }
            return lastAttempt ?: error("未产生生成结果")
        } finally {
            targetIcon.recycle()
        }
    }
}
