package dev.artplus.iconpackfiller.ui.screen

import dev.artplus.iconpackfiller.project.GenerationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `MM-dd HH:mm` 时间显示；非法值回退为「未知时间」。 */
internal fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "未知时间"
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(epochMillis))
}

/** 简短状态词（列表行用）。 */
internal fun statusText(status: GenerationStatus): String = when (status) {
    GenerationStatus.RUNNING -> "运行中"
    GenerationStatus.COMPLETED -> "完成"
    GenerationStatus.CANCELLED -> "用户取消"
    GenerationStatus.INTERRUPTED -> "进程中断"
    GenerationStatus.FAILED -> "执行失败"
}

/** 完整状态词（详情页用）。 */
internal fun statusLabel(status: GenerationStatus): String = when (status) {
    GenerationStatus.RUNNING -> "运行中"
    GenerationStatus.COMPLETED -> "已完成"
    GenerationStatus.CANCELLED -> "已取消"
    GenerationStatus.INTERRUPTED -> "已中断（进程被杀）"
    GenerationStatus.FAILED -> "执行失败"
}
