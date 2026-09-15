package dev.artplus.iconpackfiller.generate

import android.graphics.Bitmap
import dev.artplus.iconpackfiller.pack.IconStats
import dev.artplus.iconpackfiller.provider.ImageProvider
import dev.artplus.iconpackfiller.provider.ImageProviderException
import dev.artplus.iconpackfiller.provider.ImageRequest
import dev.artplus.iconpackfiller.reference.BackgroundRemoval
import dev.artplus.iconpackfiller.reference.BitmapContactSheet
import dev.artplus.iconpackfiller.reference.ContactSheetComposer
import dev.artplus.iconpackfiller.reference.KeyColorSelector
import dev.artplus.iconpackfiller.reference.OutputValidator
import dev.artplus.iconpackfiller.reference.PerceptualHash
import dev.artplus.iconpackfiller.reference.PromptTemplate
import dev.artplus.iconpackfiller.reference.ReferencePair

/**
 * 单图标生成结果。
 */
data class GeneratedIcon(
    val packageName: String,
    /** launcher activity 类名；appfilter 条目必须带它才能被启动器匹配。 */
    val activityName: String,
    val bitmap: Bitmap,
    val pngBytes: ByteArray,
    val stats: IconStats,
    val hash: Long,
    val references: List<String>,
    val attempts: Int,
)

/**
 * 端上生成管线：参考对 -> ContactSheet -> provider -> 校验 -> 重试。
 *
 * 每次尝试重选参考（[GenerationPlanner.replan]），最多 [maxAttempts] 次；
 * 全部失败抛 [GenerationException]。
 */
class IconGenerationPipeline(
    private val provider: ImageProvider,
    private val maxAttempts: Int = 3,
    private val canvasSize: Int = ContactSheetComposer.DEFAULT_CANVAS,
    private val validatorConfig: OutputValidator.Config = OutputValidator.Config(),
    /** 全局调用预算；每次真实 provider 调用前占用一次。 */
    private val budget: GenerationBudget = GenerationBudget(null),
    /**
     * 输出边长（原包图标尺寸）；null 表示保持 provider 原图尺寸。
     *
     * 生成模型固定返回 1024/1254 级大图，而图标包内通常是 192-512，
     * 不缩放会让新图标在启动器里被额外缩一次，边缘发虚。
     */
    private val outputSize: Int? = null,
    /** 是否移除模型画出来的假背景（棋盘格/纯色底）。 */
    private val removeFakeBackground: Boolean = true,
    /**
     * 模型可透明直出时为 true：提示词直接要透明底，不画键色底。
     * 抠除仍保留作安全网（真透明图会判定为"无背景"而原样通过）。
     */
    private val requestTransparentBackground: Boolean = false,
    /** 非 null 时把每次请求的拼接图落盘到该目录，供人工核对（调试用）。 */
    private val sheetDumpDir: java.io.File? = null,
    /** 请求来源快照（模型/槽位），随每次 attempt 落盘，供详情页回显与重生成。 */
    private val provenance: GenerationProvenance = GenerationProvenance(),
    /** 每次 provider 请求的结果回调（含未通过校验的），用于「结果预览」。 */
    private val onAttempt: ((GenerationAttempt) -> Unit)? = null,
) {

    /**
     * @param targetIcon 目标应用原图（正方形）
     * @param targetPair 目标应用的统计信息（用于重试时重选参考）
     * @param referencePool 候选参考对（含统计）
     * @param loadReference 按参考对加载位图（延迟加载，避免一次性占用内存）
     * @param initialPlan 首次计划
     * @param packTypicalAlpha 包内典型 alphaRatio（可选）
     */
    suspend fun generate(
        targetIcon: Bitmap,
        targetPair: ReferencePair,
        initialPlan: GenerationPlan,
        referencePool: List<ReferencePair>,
        loadReference: suspend (ReferencePair) -> Pair<Bitmap, Bitmap>?,
        packTypicalAlpha: Float? = null,
    ): GeneratedIcon {
        val targetPixels = targetIcon.toArgbArray()
        val targetHash = PerceptualHash.hashOf(targetPixels, targetIcon.width, targetIcon.height)
        // 键色按目标图标最外圈自适应三选一（目标图标在重试中不变，选一次即可）；
        // prompt 里写明该键色，生成图的假背景与图标反差最大，方便本端抠除。
        val keyColor = KeyColorSelector.select(targetPixels, targetIcon.width, targetIcon.height)
        // 对比展示用：目标原图字节，随每条 attempt 一起带给批次记录
        val sourcePngBytes = targetIcon.toPngBytes()

        var plan = initialPlan
        var lastError: String = "未执行"

        for (attempt in 1..maxAttempts) {
            if (!budget.hasRemaining()) {
                throw GenerationException("达到调用上限", retryable = false)
            }
            val loaded = plan.references.mapNotNull { reference ->
                runCatching { loadReference(reference) }.getOrNull()?.let { reference to it }
            }
            if (loaded.isEmpty()) {
                lastError = "没有可加载的参考图"
                plan = nextPlan(plan, targetPair, referencePool)
                continue
            }

            val sheet = BitmapContactSheet.compose(
                pairs = loaded.map { it.second },
                target = targetIcon,
                canvasSize = canvasSize,
            )

            sheetDumpDir?.let { dir ->
                runCatching {
                    dir.mkdirs()
                    java.io.File(dir, "${plan.target.packageName}-attempt$attempt.png")
                        .outputStream()
                        .use { out -> sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                }
            }

            val prompt = PromptTemplate.withStyleNotes(
                base = PromptTemplate.contactSheetPrompt(
                    loaded.size,
                    keyColor,
                    requestTransparentBackground,
                ),
                dominantColors = loaded.map { it.first.packStats.dominantColor },
                paddingRatio = null,
            )
            val referenceDetails = loaded.map { (ref, _) ->
                ReferenceSnapshot(
                    packageName = ref.packageName,
                    label = ref.label,
                    drawableName = ref.packDrawableName,
                    activityName = ref.activityName,
                )
            }

            if (!budget.tryAcquire()) {
                sheet.recycle()
                throw GenerationException("达到调用上限", retryable = false)
            }
            val generated = try {
                provider.generate(ImageRequest(prompt = prompt, images = listOf(sheet)))
            } catch (e: ImageProviderException) {
                lastError = e.message ?: "provider 失败"
                if (!e.retryable) throw GenerationException(lastError, retryable = false, cause = e)
                plan = nextPlan(plan, targetPair, referencePool)
                continue
            } finally {
                sheet.recycle()
            }

            // 透明化必须在校验前（影响 alpha 统计），缩放必须在校验后
            // （模型原图才满足最小边长，先缩小会被误判为"输出边长不足"）
            val normalized = removeFakeBackground(generated)
            val pixels = normalized.toArgbArray()
            val stats = IconStats.fromPixels(pixels, normalized.width, normalized.height)
            val hash = PerceptualHash.hashOf(pixels, normalized.width, normalized.height)
            val validation = OutputValidator.validate(
                generatedStats = stats,
                generatedHash = hash,
                sourceHash = targetHash,
                packTypicalAlpha = packTypicalAlpha,
                config = validatorConfig,
            )
            if (validation is OutputValidator.Result.Valid) {
                val output = downscale(normalized)
                val png = output.toPngBytes()
                onAttempt?.invoke(
                    GenerationAttempt(
                        packageName = plan.target.packageName,
                        label = plan.target.label,
                        attempt = attempt,
                        accepted = true,
                        reason = null,
                        pngBytes = png,
                        references = loaded.map { it.first.packageName },
                        sourcePngBytes = sourcePngBytes,
                        provenance = provenance,
                        prompt = prompt,
                        referenceDetails = referenceDetails,
                    ),
                )
                return GeneratedIcon(
                    packageName = plan.target.packageName,
                    activityName = plan.target.activityName,
                    bitmap = output,
                    pngBytes = png,
                    stats = stats,
                    hash = hash,
                    references = loaded.map { it.first.packageName },
                    attempts = attempt,
                )
            }

            // 校验不通过 = 模型这次的风格化结果不符合要求。
            // 重试会再花一次付费调用，且实测重试很少能救回来（同提示词、同模型），
            // 因此只记录不重试；只有网络/5xx 这类可重试错误才换参考重试。
            val reason = (validation as OutputValidator.Result.Invalid).reason
            if (onAttempt != null) {
                // downscale 会回收入参，因此只在需要记录时走一次
                val preview = downscale(normalized)
                onAttempt.invoke(
                    GenerationAttempt(
                        packageName = plan.target.packageName,
                        label = plan.target.label,
                        attempt = attempt,
                        accepted = false,
                        reason = reason,
                        pngBytes = preview.toPngBytes(),
                        references = loaded.map { it.first.packageName },
                        sourcePngBytes = sourcePngBytes,
                        provenance = provenance,
                        prompt = prompt,
                        referenceDetails = referenceDetails,
                    ),
                )
                preview.recycle()
            } else {
                normalized.recycle()
            }
            throw GenerationException("校验未通过：$reason", retryable = false)
        }

        throw GenerationException("生成失败（$maxAttempts 次尝试）：$lastError", retryable = true)
    }

    /**
     * 移除模型画出的假背景（棋盘格/纯色底）。
     *
     * 生成模型无法输出真 alpha：请求 transparent 也只得到不透明图，
     * 甚至会把"透明"画成棋盘格。因此透明化必须在本端做。
     *
     * 所有权：接管 [generated]，被替换掉的中间位图（含 [generated] 自身）会被回收。
     */
    private fun removeFakeBackground(generated: Bitmap): Bitmap {
        if (!removeFakeBackground) return generated
        val result = BackgroundRemoval.removeBorderConnectedBackground(
            pixels = generated.toArgbArray(),
            width = generated.width,
            height = generated.height,
        )
        if (!result.applied) return generated
        val argb = Bitmap.createBitmap(generated.width, generated.height, Bitmap.Config.ARGB_8888)
        argb.setPixels(result.pixels, 0, generated.width, 0, 0, generated.width, generated.height)
        generated.recycle()
        return argb
    }

    /**
     * 缩放到包内图标尺寸（校验通过后执行）。
     *
     * 所有权：接管 [normalized]，缩放后回收原图。
     */
    private fun downscale(normalized: Bitmap): Bitmap {
        val target = outputSize ?: return normalized
        if (normalized.width == target && normalized.height == target) return normalized
        val scaled = Bitmap.createScaledBitmap(normalized, target, target, true)
        if (scaled !== normalized) normalized.recycle()
        return scaled
    }

    private fun nextPlan(
        plan: GenerationPlan,
        targetPair: ReferencePair,
        pool: List<ReferencePair>,
    ): GenerationPlan = GenerationPlanner.replan(
        plan = plan,
        targetPair = targetPair,
        referencePool = pool,
    )

    private fun Bitmap.toArgbArray(): IntArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }

    private fun Bitmap.toPngBytes(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }
}