package dev.artplus.iconpackfiller.project

/**
 * 导入期构建的一条对应表记录（尚未绑定 projectId）。
 *
 * 一行 = 一个 appfilter `<item>`：component ↔ drawable ↔ 包内资源文件 ↔ density。
 */
data class PackEntrySpec(
    val componentRaw: String,
    val packageName: String,
    val activityName: String?,
    val drawableName: String,
    val resPath: String?,
    val kind: PackEntryKind,
    val density: String?,
    val inPack: Boolean,
    val origin: PackEntryOrigin,
)
