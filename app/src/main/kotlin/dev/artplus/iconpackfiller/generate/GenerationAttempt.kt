package dev.artplus.iconpackfiller.generate

/**
 * 参考对快照：用于回显「这张图是用哪几个参考编译的」，以及按原参考重新请求。
 */
data class ReferenceSnapshot(
    val packageName: String,
    val label: String? = null,
    /** 图标包内命中的 drawable 名；缺失时按包名兜底匹配。 */
    val drawableName: String? = null,
    /** 原图加载用的 activity 名。 */
    val activityName: String? = null,
)

/**
 * 请求快照：这张图由哪个供应商 / 模型 / 提示词请求得来。
 */
data class GenerationProvenance(
    val model: String? = null,
    val slotId: String? = null,
    val slotName: String? = null,
)

/**
 * 一次 provider 请求的结果记录（无论是否通过校验）。
 *
 * 保留的意义：模型对同一目标可能给出风格差异很大的结果，
 * 被本地校验判为不合格的那张未必是废图——用户应能自己看过后决定。
 * 也让「网关显示成功、界面却报失败」这类情况可直接对照。
 */
data class GenerationAttempt(
    val packageName: String,
    val label: String?,
    /** 第几次尝试（1 起）。 */
    val attempt: Int,
    /** 是否通过校验并进入补全包。 */
    val accepted: Boolean,
    /** 未通过时的原因；[accepted] 为 true 时为 null。 */
    val reason: String?,
    /** 后处理 + 缩放后的 PNG 字节（可直接预览，与写入包内的图一致）。 */
    val pngBytes: ByteArray,
    /** 本次使用的参考应用包名。 */
    val references: List<String>,
    /** 目标应用原图的 PNG 字节（对比展示用）；加载失败时为 null。 */
    val sourcePngBytes: ByteArray? = null,
    /** 请求快照：模型 / 槽位。 */
    val provenance: GenerationProvenance = GenerationProvenance(),
    /** 发给模型的完整提示词。 */
    val prompt: String? = null,
    /** 参考对明细（回显 + 按原参考重生成）。 */
    val referenceDetails: List<ReferenceSnapshot> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GenerationAttempt) return false
        return packageName == other.packageName &&
            label == other.label &&
            attempt == other.attempt &&
            accepted == other.accepted &&
            reason == other.reason &&
            pngBytes.contentEquals(other.pngBytes) &&
            references == other.references &&
            sourcePngBytes.contentEquals(other.sourcePngBytes) &&
            provenance == other.provenance &&
            prompt == other.prompt &&
            referenceDetails == other.referenceDetails
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + attempt
        result = 31 * result + accepted.hashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        result = 31 * result + pngBytes.contentHashCode()
        result = 31 * result + references.hashCode()
        result = 31 * result + (sourcePngBytes?.contentHashCode() ?: 0)
        result = 31 * result + provenance.hashCode()
        result = 31 * result + (prompt?.hashCode() ?: 0)
        result = 31 * result + referenceDetails.hashCode()
        return result
    }
}
