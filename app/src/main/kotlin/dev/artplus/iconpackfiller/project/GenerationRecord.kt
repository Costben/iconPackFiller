package dev.artplus.iconpackfiller.project

/**
 * 生成历史对 UI 的投影视图。
 *
 * 持久化事实源是 Room（[dev.artplus.iconpackfiller.project.db.GenerationEntity] 等）；
 * 这里只是把一条 Generation 及其 Attempt 组装成界面直接可用的形状，
 * 命名沿用旧 `BatchRecord` 的字段以保持行为不变（Phase 6 再按项目/生成重构页面）。
 */
data class AttemptRecord(
    val packageName: String,
    val label: String?,
    val attempt: Int,
    val accepted: Boolean,
    val reason: String?,
    /** 生成图 PNG 文件名（相对生成目录的 `att/`）。 */
    val pngFile: String?,
    /** 目标应用原图 PNG 文件名（相对生成目录的 `att/`），用于对比展示。 */
    val sourceFile: String?,
    val references: List<String>,
    /** 请求快照（模型/槽位），供「重新生成」回显与复用。 */
    val model: String? = null,
    val slotId: String? = null,
    val slotName: String? = null,
    /** 发给模型的完整提示词。 */
    val prompt: String? = null,
    /** 参考对明细（包名 + drawable + activity），用于按原参考重生成。 */
    val referenceDetails: List<dev.artplus.iconpackfiller.generate.ReferenceSnapshot> = emptyList(),
)

/**
 * 一条 Generation 的全量投影（含其 Attempt）。
 */
data class GenerationRecord(
    val id: String,
    /** 所属项目 id（文件访问需要）。 */
    val projectId: String,
    /** 图标包显示名（如 "Aura"）。 */
    val packLabel: String,
    /** 图标包包名。 */
    val packPackage: String,
    /** 创建时间（epoch millis）。 */
    val createdAt: Long,
    val status: GenerationStatus,
    /** 本次要生成的目标总数（计划数）。 */
    val plannedCount: Int,
    val generatedCount: Int,
    val failedCount: Int,
    /** 输出 APK 文件名（相对生成目录）；未打包为 null。 */
    val outputApk: String?,
    /** 输出 APK 的展示名（导出文件名）。 */
    val outputApkName: String?,
    /** 诊断信息（失败原因等），供详情页展示。 */
    val diagnostics: List<String>,
    val attempts: List<AttemptRecord>,
) {
    /** 已完成比例（0..1），供进度条使用。 */
    val progress: Float
        get() {
            if (plannedCount <= 0) return if (status == GenerationStatus.RUNNING) 0f else 1f
            val done = generatedCount + failedCount
            return (done.toFloat() / plannedCount).coerceIn(0f, 1f)
        }

    val finished: Boolean get() = status.isTerminal
}
