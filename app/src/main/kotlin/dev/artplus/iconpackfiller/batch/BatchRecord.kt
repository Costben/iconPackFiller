package dev.artplus.iconpackfiller.batch

/**
 * 批次生命周期状态。
 */
enum class BatchStatus {
    /** 运行中（进程被杀后遗留在 RUNNING 的记录会在下次启动时标记为 [INTERRUPTED]）。 */
    RUNNING,

    /** 正常跑完（可能部分应用生成失败，但流程走到打包/结束）。 */
    COMPLETED,

    /** 用户取消。 */
    CANCELLED,

    /** 进程中断（崩溃/被系统杀），无法恢复。 */
    INTERRUPTED,

    /** 执行中抛异常终止。 */
    FAILED,
}

/**
 * 一条生成尝试的持久化记录。
 *
 * 图不放进 JSON：PNG 字节写同目录文件，JSON 只存文件名与元信息。
 * 这样 `SharedPreferences`/JSON 保持小而可读，图也能单独被 SAF 分享/清理。
 */
data class AttemptRecord(
    val packageName: String,
    val label: String?,
    val attempt: Int,
    val accepted: Boolean,
    val reason: String?,
    /** 生成图 PNG 文件名（相对批次目录）。 */
    val pngFile: String?,
    /** 目标应用原图 PNG 文件名（相对批次目录），用于对比展示。 */
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
 * 一个批次（一次「执行任务」）的全量记录。
 */
data class BatchRecord(
    val id: String,
    /** 图标包显示名（如 "Aura"）。 */
    val packLabel: String,
    /** 图标包包名。 */
    val packPackage: String,
    /** 创建时间（epoch millis）。 */
    val createdAt: Long,
    val status: BatchStatus,
    /** 本次要生成的目标总数（计划数）。 */
    val plannedCount: Int,
    val generatedCount: Int,
    val failedCount: Int,
    /** 输出 APK 文件名（相对批次目录）；未打包为 null。 */
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
            if (plannedCount <= 0) return if (status == BatchStatus.RUNNING) 0f else 1f
            val done = generatedCount + failedCount
            return (done.toFloat() / plannedCount).coerceIn(0f, 1f)
        }

    val finished: Boolean get() = status != BatchStatus.RUNNING
}
