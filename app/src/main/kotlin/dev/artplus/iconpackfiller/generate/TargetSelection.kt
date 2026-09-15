package dev.artplus.iconpackfiller.generate

import dev.artplus.iconpackfiller.coverage.LaunchableApp

/**
 * 目标应用选择（「确认范围」页勾选）。
 *
 * 纯逻辑，可 JVM 单测。选中状态用**稳定 key** 表示，
 * 因为同一包可能有多条 launcher activity（别名），只按包名无法区分。
 */
object TargetSelection {

    /** 单个可生成目标的稳定 key：`package/activity`。 */
    fun keyOf(app: LaunchableApp): String = "${app.packageName}/${app.activityName}"

    /**
     * 按选中集合过滤 [apps]，保持原顺序。
     *
     * @param selected 选中 key 集合；null 或包含全部 key 时返回原列表
     */
    fun filter(apps: List<LaunchableApp>, selected: Set<String>?): List<LaunchableApp> {
        if (selected == null) return apps
        return apps.filter { keyOf(it) in selected }
    }

    /** 全部 key（用于「全选」）。 */
    fun allKeys(apps: List<LaunchableApp>): Set<String> = apps.mapTo(LinkedHashSet()) { keyOf(it) }

    /**
     * 按关键字过滤：应用名或包名包含关键字即命中（忽略大小写）。
     *
     * 关键字为空（或全空白）时返回原列表。
     */
    fun filterByQuery(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { matchesQuery(it, q) }
    }

    /** 单个应用是否命中关键字（应用名或包名，忽略大小写）。 */
    fun matchesQuery(app: LaunchableApp, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return app.label?.contains(q, ignoreCase = true) == true ||
            app.packageName.contains(q, ignoreCase = true)
    }

    /**
     * 选中数量；[selected] 为 null（未初始化 = 视为全选）时返回 [apps] 全量。
     */
    fun selectedCount(apps: List<LaunchableApp>, selected: Set<String>?): Int =
        if (selected == null) apps.size else apps.count { keyOf(it) in selected }

    /** 是否全选。 */
    fun isAllSelected(apps: List<LaunchableApp>, selected: Set<String>?): Boolean =
        selected == null || apps.all { keyOf(it) in selected }
}
