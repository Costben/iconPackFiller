package dev.artplus.iconpackfiller.ui.navigation

import androidx.navigation3.runtime.NavKey

/**
 * 导航目的地。One-shot 工具应用，不做进程死亡恢复（任务协程本身无法恢复），
 * 因此纯 `data object`，不加序列化。
 *
 * 导航语义以「图标包 = 持久项目」为中心：
 * [Projects]（项目列表）→ [Project]（项目详情 / 生成历史）→ [Generation]（某次生成的
 * 图标筛选与对比）→ [Compare]（同一目标跨生成并排看图）。
 */
sealed interface Route : NavKey {
    /** 图标包选择 + 设置入口。 */
    data object Pick : Route

    /** 覆盖率确认 + 开始生成。 */
    data object Review : Route

    /** 生成 + 打包进行中。 */
    data object Progress : Route

    /** 完成与导出（展示当前生成的对比结果）。 */
    data object Done : Route

    /** 项目列表（导入即建项目）。 */
    data object Projects : Route

    /** 项目详情：对应表概览 + 生成历史 + 当前指向。 */
    data class Project(val id: String) : Route

    /** 某次生成详情：按包名/组件/accepted 筛选图标 + 生成对比。 */
    data class Generation(val id: String) : Route

    /** 同一目标跨 Generation 并排对比。 */
    data class Compare(
        val projectId: String,
        val packageName: String,
        val activityName: String?,
    ) : Route

    /** 设置。 */
    data object Settings : Route

    companion object {
        val START: Route = Pick
    }
}
