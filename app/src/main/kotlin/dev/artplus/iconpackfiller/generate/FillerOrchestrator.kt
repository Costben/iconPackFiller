package dev.artplus.iconpackfiller.generate

import android.content.Context
import dev.artplus.iconpackfiller.coverage.AppScanner
import dev.artplus.iconpackfiller.coverage.CoverageCalculator
import dev.artplus.iconpackfiller.coverage.CoverageReport
import dev.artplus.iconpackfiller.coverage.CoverageRules
import dev.artplus.iconpackfiller.pack.ApkSigner
import dev.artplus.iconpackfiller.pack.DrawableDirectoryDetector
import dev.artplus.iconpackfiller.pack.IconInjection
import dev.artplus.iconpackfiller.pack.IconPackPacker
import dev.artplus.iconpackfiller.pack.PackNaming
import dev.artplus.iconpackfiller.provider.ImageProvider
import dev.artplus.iconpackfiller.reference.ReferencePair
import java.io.File

/**
 * 端到端编排：扫描 → 覆盖率 → 参考对 → 生成 → 打包 → 签名。
 *
 * 所有中间产物放在 [workDir] 下；完成后由调用方导出（SAF）。
 */
class FillerOrchestrator(
    private val context: Context,
    private val workDir: File,
    private val provider: ImageProvider,
    private val packer: IconPackPacker = IconPackPacker(),
    /** 请求来源快照（模型/槽位），随 attempt 落盘。 */
    private val provenance: GenerationProvenance = GenerationProvenance(),
    /** 生成失败原因诊断回调（不打印 apiKey）。 */
    private val onDiagnostic: (String) -> Unit = { message ->
        android.util.Log.w("IconPackFiller", message)
    },
) {

    /** 调试开关：导出每次请求的拼接图。正常使用保持 false。 */
    private val sheetDumpEnabled = true

    /**
     * 输入：图标包来源（已安装包名 或 本地 APK 文件）+ 规则 + 参数。
     */
    data class Input(
        val installedPackage: String? = null,
        val apkFile: File? = null,
        val rules: CoverageRules = CoverageRules.DEFAULT,
        val referencePairCount: Int = 2,
        val maxGenerationAttempts: Int = 3,
        val callLimit: Int? = null,
        /**
         * 仅生成这些目标（`package/activity` 稳定 key）；null 表示报告内全部。
         *
         * 只影响**生成**范围：参考对仍从整个图标包的已覆盖应用中挑选，
         * 这样用户只勾少量应用时画风参考依然充足。
         */
        val selectedTargets: Set<String>? = null,
        /**
         * 当前模型的透明直出是否生效（调用方按槽位声明 + 预设映射 resolve 好传入）。
         * 生效时提示词直接要透明底，不画键色底。
         */
        val requestTransparentBackground: Boolean = false,
        /**
         * 「确认范围」页固定下来的参考对（重新抽样 / 选择应用的结果）。
         *
         * 非空时整批都用这组参考，不再按目标配色自适应选择；
         * 与 [ReferencePairSelectorConfig] 互斥（有 override 时忽略 pairCount）。
         */
        val referenceOverride: List<ReferencePair>? = null,
    ) {
        init {
            require((installedPackage == null) != (apkFile == null)) {
                "必须且只能指定 installedPackage 或 apkFile 之一"
            }
        }
    }

    /** 进度事件（UI 用）。 */
    sealed class Progress {
        data class ScanDone(val report: CoverageReport) : Progress()
        data class PlanDone(val planCount: Int, val referenceCount: Int) : Progress()
        data class GenerationStarted(val total: Int) : Progress()
        data class GenerationProgress(val done: Int, val total: Int, val succeeded: Int, val failed: Int) : Progress()
        data class GenerationDone(val generated: Int, val failed: Int) : Progress()
        data class LimitReached(val description: String) : Progress()
        data class PackStarted(val injecting: Int) : Progress()
        data class PackDone(val result: dev.artplus.iconpackfiller.pack.PackResult, val signedApk: File) : Progress()
        data class Failed(val stage: String, val message: String) : Progress()
    }

    /** 中间结果（M6 UI 展示 + M5 打包复用）。 */
    data class Session(
        val report: CoverageReport,
        val plans: List<GenerationPlan>,
        val generated: List<GeneratedIcon>,
        /** 每次 provider 请求的结果（含未通过校验的），用于结果预览。 */
        val attempts: List<GenerationAttempt>,
        val packResult: dev.artplus.iconpackfiller.pack.PackResult?,
        val signedApk: File?,
        val originalPackage: String,
        val originalVersionCode: Int,
    )

    /**
     * 执行完整流程。生成失败的应用不阻塞打包（只注入成功项）。
     *
     * @param onProgress 进度回调（主线程调度由调用方负责）
     * @param shouldCancel 取消检查（每完成一个生成任务调用一次）
     * @param onAttempt 每次 provider 请求完成的回调（含未通过校验的）；
     *   供调用方**增量**落盘批次记录——取消/崩溃时也能保住已生成的图。
     */
    suspend fun run(
        input: Input,
        onProgress: (Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        onAttempt: ((GenerationAttempt) -> Unit)? = null,
    ): Session {
        val source = openSource(input)
            ?: run {
                onProgress(Progress.Failed("open", "无法打开图标包"))
                error("无法打开图标包")
            }
        source.use { pack ->
            // 1) 扫描 + 覆盖率
            val apps = AppScanner(context).scan()
            val report = CoverageCalculator.compute(
                apps = apps,
                appFilter = pack.document,
                availableDrawables = pack.availableDrawables,
                rules = input.rules,
            )
            onProgress(Progress.ScanDone(report))
            if (shouldCancel()) return emptySession(report, pack.packageName, pack.versionCode)

            // 2) 参考对 + 计划
            val referencePool = ReferencePoolBuilder.build(context, pack, report, onDiagnostic)
            val selectedTargets = TargetSelection.filter(report.unmatched, input.selectedTargets)
            val targetPairs = selectedTargets.mapNotNull { app ->
                ReferencePoolBuilder.loadTargetPair(context, app)?.let { app.packageName to it }
            }.toMap()
            val override = input.referenceOverride?.takeIf { it.isNotEmpty() }
            val plans = if (override != null) {
                GenerationPlanner.planFixed(
                    targets = selectedTargets,
                    targetPairs = targetPairs,
                    references = override,
                )
            } else {
                GenerationPlanner.plan(
                    targets = selectedTargets,
                    referencePool = referencePool,
                    targetPairs = targetPairs,
                    config = ReferencePairSelectorConfig(pairCount = input.referencePairCount),
                )
            }
            onProgress(Progress.PlanDone(plans.size, override?.size ?: referencePool.size))
            if (referencePool.isEmpty()) {
                onDiagnostic("参考池为空：matched=${report.matched.size}")
            }
            if (shouldCancel()) return emptySession(report, pack.packageName, pack.versionCode)

            // 3) 生成
            onProgress(Progress.GenerationStarted(plans.size))
            val generated = ArrayList<GeneratedIcon>()
            var failed = 0
            var limitReached = false
            val total = plans.size
            // 预算按真实 API 调用计（含重试），避免重试把成本放大 maxAttempts 倍
            val budget = GenerationBudget(input.callLimit)
            // 生成模型只给 1024/1254 大图；缩到原包尺寸，避免启动器二次缩放发虚
            val convention = DrawableDirectoryDetector.detect(pack.sourceApk)
            val attempts = java.util.Collections.synchronizedList(arrayListOf<GenerationAttempt>())
            val pipeline = IconGenerationPipeline(
                provider = provider,
                maxAttempts = input.maxGenerationAttempts,
                budget = budget,
                outputSize = convention.pixelSize,
                requestTransparentBackground = input.requestTransparentBackground,
                // 调试开关：把发给模型的拼接图落盘到 cache/sheet-dump，供人工核对
                sheetDumpDir = File(context.cacheDir, "sheet-dump").takeIf { sheetDumpEnabled },
                provenance = provenance,
                onAttempt = { attempt ->
                    attempts.add(attempt)
                    onAttempt?.invoke(attempt)
                },
            )
            onDiagnostic(
                "包内图标约定：${convention.directory}" +
                    "（尺寸 ${convention.pixelSize ?: "未知"}，采样 ${convention.sampleCount}）",
            )
            for ((index, plan) in plans.withIndex()) {
                if (shouldCancel()) break
                if (!budget.hasRemaining()) {
                    limitReached = true
                    onDiagnostic("达到调用上限（${input.callLimit}），剩余 ${total - index} 个应用跳过")
                    break
                }
                val targetIcon = AppIconLoader.load(context, plan.target.packageName, plan.target.activityName)
                val targetPair = targetPairs[plan.target.packageName]
                if (targetIcon == null || targetPair == null) {
                    failed++
                    onDiagnostic("${plan.target.packageName}: 目标图标加载失败")
                    onProgress(Progress.GenerationProgress(index + 1, total, generated.size, failed))
                    continue
                }
                try {
                    generated.add(
                        pipeline.generate(
                            targetIcon = targetIcon,
                            targetPair = targetPair,
                            initialPlan = plan,
                            referencePool = referencePool,
                            loadReference = { reference ->
                                ReferencePoolBuilder.loadReferenceBitmaps(context, pack, reference, onDiagnostic)
                            },
                        ),
                    )
                } catch (e: GenerationException) {
                    failed++
                    onDiagnostic("${plan.target.packageName}: ${e.message?.take(300)}")
                } catch (e: Exception) {
                    failed++
                    onDiagnostic("${plan.target.packageName}: ${e::class.simpleName} ${e.message?.take(300)}")
                } finally {
                    targetIcon.recycle()
                }
                onProgress(Progress.GenerationProgress(index + 1, total, generated.size, failed))
            }
            if (limitReached) {
                onProgress(Progress.LimitReached(budget.limitDescription()))
            }
            onProgress(Progress.GenerationDone(generated.size, failed))

            // 4) 打包 + 签名
            onProgress(Progress.PackStarted(generated.size))
            val unsigned = File(workDir, "unsigned.apk")
            val signed = File(workDir, "signed.apk")
            val injections = generated.mapIndexed { index, icon ->
                IconInjection(
                    // 带 activity：启动器按 ComponentName 匹配，包级写法会被跳过
                    component = PackNaming.componentInfoFor(icon.packageName, icon.activityName),
                    drawableName = PackNaming.drawableNameFor(index),
                    pngBytes = icon.pngBytes,
                )
            }
            val packResult = packer.pack(
                sourceApk = pack.sourceApk,
                outputApk = unsigned,
                injections = injections,
            )
            val material = ApkSigner.loadFromAssets { context.assets.open("filler-signing.p12") }
            ApkSigner.sign(unsigned, signed, material)
            onProgress(Progress.PackDone(packResult, signed))

            return Session(
                report = report,
                plans = plans,
                generated = generated,
                attempts = attempts.toList(),
                packResult = packResult,
                signedApk = signed,
                originalPackage = pack.packageName,
                originalVersionCode = pack.versionCode,
            )
        }
    }

    private fun openSource(input: Input): IconPackSource? = when {
        input.installedPackage != null -> IconPackSource.open(context, input.installedPackage)
        input.apkFile != null -> IconPackSource.open(input.apkFile)
        else -> null
    }

    private fun emptySession(
        report: CoverageReport,
        originalPackage: String,
        originalVersionCode: Int,
    ) = Session(
        report = report,
        plans = emptyList(),
        generated = emptyList(),
        attempts = emptyList(),
        packResult = null,
        signedApk = null,
        originalPackage = originalPackage,
        originalVersionCode = originalVersionCode,
    )
}