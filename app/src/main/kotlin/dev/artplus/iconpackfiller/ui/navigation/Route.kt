package dev.artplus.iconpackfiller.ui.navigation

import androidx.navigation3.runtime.NavKey

/**
 * 导航目的地。One-shot 工具应用，不做进程死亡恢复（任务协程本身无法恢复），
 * 因此纯 `data object`，不加序列化。
 */
sealed interface Route : NavKey {
    /** 图标包选择 + 设置入口。 */
    data object Pick : Route

    /** 覆盖率确认 + 开始生成。 */
    data object Review : Route

    /** 生成 + 打包进行中。 */
    data object Progress : Route

    /** 完成与导出（展示当前批次的对比结果）。 */
    data object Done : Route

    /** 批次记录列表（历史任务）。 */
    data object Batches : Route

    /** 某个批次的详情：进度 + 生成对比。 */
    data class BatchDetail(val id: String) : Route

    /** 设置。 */
    data object Settings : Route

    companion object {
        val START: Route = Pick
    }
}
