package dev.artplus.iconpackfiller.project

/**
 * 一次 Generation 中单个目标图标的落库规格（尚未绑定 generationId）。
 *
 * 一行 = 本次生成的一个目标：accepted 表示该目标是否产出并写入补全包；
 * [drawableName] 是写入输出包的 drawable 名（`ap_gen_<计划序号>`），未通过时为 null。
 */
data class GenerationIconSpec(
    val packageName: String,
    val activityName: String? = null,
    val label: String? = null,
    val drawableName: String? = null,
    val accepted: Boolean,
    val reason: String? = null,
)

/**
 * 单次 provider 请求的归并输入。
 *
 * 与 [dev.artplus.iconpackfiller.generate.GenerationAttempt] 及 AttemptEntity 解耦，
 * 让 [GenerationIconBuilder] 保持纯逻辑、可 JVM 单测。
 */
data class GenerationIconOutcome(
    val packageName: String,
    val label: String? = null,
    val attempt: Int = 0,
    val accepted: Boolean,
    val reason: String? = null,
)
