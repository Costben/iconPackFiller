package dev.artplus.iconpackfiller.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.batch.BatchRecord
import dev.artplus.iconpackfiller.batch.BatchStatus
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批次列表：每次「执行任务」一条，可点进详情看进度与生成对比。
 */
@Composable
fun BatchesScreen(
    state: UiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    MiuixScreen(
        title = "历史任务",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = colorScheme.onSurface,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                )
            }
        },
    ) {
        if (state.batches.isEmpty()) {
            item(key = "empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    BasicComponent(
                        title = "还没有任务记录",
                        summary = "每次生成都会自动记录，可随时回来查看进度和结果",
                    )
                }
            }
        } else {
            item(key = "title") {
                SmallTitle(text = "共 ${state.batches.size} 个任务", modifier = Modifier.padding(top = 8.dp))
            }
            items(state.batches, key = { it.id }) { batch ->
                BatchCard(batch) { viewModel.openBatch(batch.id) }
            }
        }
    }
}

@Composable
private fun BatchCard(batch: BatchRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        onClick = onClick,
        showIndication = true,
    ) {
        BasicComponent(
            title = batch.packLabel,
            summary = buildString {
                append(statusText(batch.status))
                append(" · ")
                append(formatTime(batch.createdAt))
                if (batch.plannedCount > 0) {
                    append(" · 成功 ${batch.generatedCount}/失败 ${batch.failedCount}/共 ${batch.plannedCount}")
                }
            },
            endActions = {
                Text(
                    text = when (batch.status) {
                        BatchStatus.RUNNING -> "生成中"
                        BatchStatus.COMPLETED -> "已完成"
                        BatchStatus.CANCELLED -> "已取消"
                        BatchStatus.INTERRUPTED -> "已中断"
                        BatchStatus.FAILED -> "失败"
                    },
                    color = statusColor(batch.status),
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
        )
    }
}

@Composable
private fun statusColor(status: BatchStatus) = when (status) {
    BatchStatus.RUNNING -> colorScheme.primary
    BatchStatus.COMPLETED -> colorScheme.primary
    BatchStatus.CANCELLED, BatchStatus.INTERRUPTED -> colorScheme.onSurfaceVariantSummary
    BatchStatus.FAILED -> colorScheme.error
}

private fun statusText(status: BatchStatus): String = when (status) {
    BatchStatus.RUNNING -> "运行中"
    BatchStatus.COMPLETED -> "完成"
    BatchStatus.CANCELLED -> "用户取消"
    BatchStatus.INTERRUPTED -> "进程中断"
    BatchStatus.FAILED -> "执行失败"
}

internal fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "未知时间"
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(epochMillis))
}
