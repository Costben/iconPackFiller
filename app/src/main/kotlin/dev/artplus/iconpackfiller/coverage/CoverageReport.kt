package dev.artplus.iconpackfiller.coverage

/**
 * 待扫描的可启动应用条目。与 Android 类型解耦，便于 JVM 单测。
 */
data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val label: String?,
    val isSystemApp: Boolean,
    /** 同一包内是否存在多个 LAUNCHER activity（别名）。 */
    val aliasCount: Int = 1,
    /** 该 activity 是否被声明为包内默认入口。 */
    val isDefaultAlias: Boolean = true,
    /** 图标可加载性；null 表示未检查。 */
    val hasIcon: Boolean? = null,
    /** 应用分类（ApplicationInfo.category，供 M4 参考对选择）。 */
    val category: Int? = null,
)

/**
 * 排除原因。用于 UI 可解释展示。
 */
enum class ExcludeReason(val label: String) {
    SYSTEM_APP("系统应用"),
    NO_ICON("无可加载图标"),
    USER_BLACKLIST("用户黑名单"),
    NON_DEFAULT_ALIAS("同包多别名，非默认项"),
    MISSING_DRAWABLE("appfilter 引用的 drawable 缺失"),
    DISABLED("应用已禁用"),
}

/**
 * 扫描结果三组。
 */
data class CoverageReport(
    /** 已被图标包覆盖（精确或包级命中）。 */
    val matched: List<CoveredApp>,
    /** 未覆盖，待生成队列。 */
    val unmatched: List<LaunchableApp>,
    /** 被排除，附原因。 */
    val excluded: List<ExcludedApp>,
) {
    val total: Int get() = matched.size + unmatched.size + excluded.size

    val coverageRatio: Float
        get() = if (total == 0) 0f else matched.size.toFloat() / total
}

data class CoveredApp(
    val app: LaunchableApp,
    val drawableName: String,
    /** 命中方式：exact / package。 */
    val matchKind: dev.artplus.iconpackfiller.pack.MatchKind,
)

data class ExcludedApp(
    val app: LaunchableApp,
    val reason: ExcludeReason,
)