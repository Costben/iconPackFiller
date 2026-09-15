package dev.artplus.iconpackfiller.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.reference.ReferencePair
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「选择应用」对话框：从图标包已覆盖的应用里任选参考对（最多 [maxCount] 组）。
 *
 * 每行展示「原图 → 包内生效图」，避免盲选；选中的参考对整批固定使用
 * （左原图 / 右包内重绘一起发给模型）。确认后由调用方合成采样图并写入批次参考。
 *
 * @param candidates null 表示参考池还在构建；空表表示没有可用候选
 * @param icons 候选缩略图（包名 → 原图/包内），未加载时显示占位
 * @param initialSelected 已选包名（重新打开时回显）
 */
@Composable
fun ReferencePickerDialog(
    show: Boolean,
    candidates: List<ReferencePair>?,
    maxCount: Int,
    initialSelected: Set<String>,
    icons: Map<String, ReferenceIconPair>,
    onConfirm: (List<ReferencePair>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每次打开都从当前批次参考回显，避免上次的临时勾选残留
    var selected by remember(show) { mutableStateOf(initialSelected) }
    val sorted = remember(candidates) {
        candidates?.sortedBy { it.label ?: it.packageName }
    }
    val limit = maxCount.coerceAtLeast(1)

    WindowDialog(
        show = show,
        title = "选择参考应用",
        summary = "从图标包已覆盖的应用里任选（最多 $limit 组）；选中的参考整批使用",
        onDismissRequest = onDismiss,
    ) {
        when {
            sorted == null -> Text(
                text = "正在准备参考池…",
                style = MiuixTheme.textStyles.footnote1,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            sorted.isEmpty() -> Text(
                text = "图标包没有可用于参考的已覆盖应用",
                style = MiuixTheme.textStyles.footnote1,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(sorted, key = { it.packageName }) { candidate ->
                        val checked = candidate.packageName in selected
                        val reached = selected.size >= limit && !checked
                        val icon = icons[candidate.packageName]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            onClick = {
                                if (checked) {
                                    selected = selected - candidate.packageName
                                } else if (!reached) {
                                    selected = selected + candidate.packageName
                                }
                            },
                            showIndication = true,
                        ) {
                            BasicComponent(
                                title = candidate.label ?: candidate.packageName,
                                summary = candidate.packageName,
                                startAction = {
                                    ReferenceIconPairPreview(icon)
                                },
                                endActions = {
                                    Checkbox(
                                        state = ToggleableState(checked),
                                        // 整行已是点击区域，再挂回调会切换两次
                                        onClick = null,
                                    )
                                },
                            )
                        }
                    }
                }
                Text(
                    text = "已选 ${selected.size}/$limit",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "确定",
                onClick = {
                    onConfirm(sorted.orEmpty().filter { it.packageName in selected })
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 行首「原图 → 包内生效图」缩略图；未加载时显示占位标签。 */
@Composable
private fun ReferenceIconPairPreview(icon: ReferenceIconPair?) {
    Row(
        modifier = Modifier.padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tile(loadBytes = { icon?.original }, fallbackLabel = "原图", size = 36)
        Text(
            text = "→",
            style = MiuixTheme.textStyles.footnote1,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Tile(loadBytes = { icon?.pack }, fallbackLabel = "包内", size = 36)
    }
}
