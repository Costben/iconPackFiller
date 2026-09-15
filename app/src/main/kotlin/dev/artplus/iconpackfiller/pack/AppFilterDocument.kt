package dev.artplus.iconpackfiller.pack

/**
 * appfilter.xml 的单条 `<item>`。
 */
data class AppFilterItem(
    val component: ComponentKey,
    val drawableName: String,
    /** 原始 component 字符串，透传打包时原样保留。 */
    val rawComponent: String,
)

/**
 * appfilter.xml 中的扩展节点（iconback / iconmask / iconupon / scale）。
 * 打包时必须原样透传，保持原包条目顺序。
 */
data class AppFilterExtras(
    val iconback: List<String> = emptyList(),
    val iconmask: List<String> = emptyList(),
    val iconupon: List<String> = emptyList(),
    val scale: Float? = null,
) {
    val isEmpty: Boolean
        get() = iconback.isEmpty() && iconmask.isEmpty() && iconupon.isEmpty() && scale == null
}

/**
 * 完整解析结果。items 保持文件顺序；index 用于 O(1) 匹配。
 */
data class AppFilterDocument(
    val items: List<AppFilterItem>,
    val extras: AppFilterExtras,
) {
    /** 精确组件键 -> 最后一条同名条目（后写覆盖，与启动器一致）。 */
    private val exactIndex: Map<ComponentKey, AppFilterItem> by lazy {
        items.filterNot { it.component.isPackageLevel }.associateBy { it.component }
    }

    /** 包级键 -> 最后一条包级条目。 */
    private val packageIndex: Map<String, AppFilterItem> by lazy {
        items.filter { it.component.isPackageLevel }.associateBy { it.component.packageName }
    }

    /**
     * 精确 `ComponentInfo{pkg/activity}` 优先，`ComponentInfo{pkg}` 兜底。
     */
    fun match(packageName: String, activityName: String?): AppFilterItem? {
        val pkg = ComponentKey.normalizePackage(packageName)
        if (activityName != null) {
            val activity = ComponentKey.normalizeActivity(pkg, activityName)
            exactIndex[ComponentKey(pkg, activity)]?.let { return it }
        }
        return packageIndex[pkg]
    }

    /**
     * 返回命中条目与命中方式；未命中返回 null。
     */
    fun matchDetailed(packageName: String, activityName: String?): MatchResult? {
        val pkg = ComponentKey.normalizePackage(packageName)
        if (activityName != null) {
            val activity = ComponentKey.normalizeActivity(pkg, activityName)
            exactIndex[ComponentKey(pkg, activity)]?.let {
                return MatchResult(it, MatchKind.EXACT)
            }
        }
        packageIndex[pkg]?.let { return MatchResult(it, MatchKind.PACKAGE_LEVEL) }
        return null
    }

    fun containsDrawable(drawableName: String): Boolean =
        items.any { it.drawableName == drawableName }
}

data class MatchResult(
    val item: AppFilterItem,
    val kind: MatchKind,
)

enum class MatchKind { EXACT, PACKAGE_LEVEL }