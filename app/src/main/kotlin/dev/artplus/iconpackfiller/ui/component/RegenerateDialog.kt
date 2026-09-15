package dev.artplus.iconpackfiller.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.artplus.iconpackfiller.generate.ReferenceSnapshot
import dev.artplus.iconpackfiller.settings.ProviderSlot
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 参考对采样图（PNG 字节）：左原图、右包内重绘；加载失败为 null。 */
data class ReferenceIconPair(val original: ByteArray?, val pack: ByteArray?)

/** 「重新取样」的一次刷新：抽中的参考明细 + 对应的采样图。 */
data class SampledReferences(
    val references: List<ReferenceSnapshot>,
    val icons: List<ReferenceIconPair>,
)

/**
 * 「生成详情」弹窗：点/长按对比行的 ⋯（或长按生成图）打开。
 *
 * - 本次结果：状态、未采纳原因、诊断；
 * - 生成来源：模型 / 槽位、参考对采样图（原图 → 包内重绘）、发给模型的提示词；
 * - 操作：「重新取样」点一次换一组参考（纯本地，不发请求），不满意就再点；
 *   看顺眼后「重新生成」用当下这组参考 + 选定的模型发请求。
 *
 * 「重新生成」的模型列表 = 设置里配置好的供应商槽位（默认原请求槽位），
 * 与「确认范围」页激活的是哪个槽位无关；请求需要 baseUrl + key + model 一套齐全，
 * 所以这里只列槽位，不列网关的模型目录。
 */
@Composable
fun RegenerateDialog(
    title: String,
    accepted: Boolean,
    attemptIndex: Int,
    reason: String?,
    diagnostics: List<String>,
    model: String?,
    slotName: String?,
    prompt: String?,
    references: List<ReferenceSnapshot>,
    referenceNames: List<String>,
    slots: List<ProviderSlot>,
    initialSlotId: String?,
    busy: Boolean,
    loadReferenceIcons: suspend (List<ReferenceSnapshot>) -> List<ReferenceIconPair>,
    onSample: suspend () -> SampledReferences?,
    onDismiss: () -> Unit,
    onRegenerate: (slotId: String, references: List<ReferenceSnapshot>?) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(modifier = Modifier.fillMaxWidth(0.92f)) {
            var picking by remember { mutableStateOf(false) }
            var sampling by remember { mutableStateOf(false) }
            var sampled by remember { mutableStateOf<SampledReferences?>(null) }
            val scope = rememberCoroutineScope()
            var selectedIndex by remember {
                mutableIntStateOf(
                    slots.indexOfFirst { it.id == initialSlotId }.coerceAtLeast(0),
                )
            }

            val original = remember(references, referenceNames) {
                if (references.isNotEmpty()) references
                else referenceNames.map { ReferenceSnapshot(it) }
            }
            val originalIcons by produceState<List<ReferenceIconPair>?>(initialValue = null, original) {
                value = runCatching { loadReferenceIcons(original) }.getOrDefault(emptyList())
            }

            val effective = sampled?.references ?: original
            val icons = sampled?.icons ?: originalIcons

            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (picking) "选择模型" else "生成详情",
                    style = MiuixTheme.textStyles.title3,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary,
                )

                if (!picking) {
                    InfoRow(
                        label = "本次结果",
                        value = when {
                            accepted && attemptIndex > 1 -> "已采纳 · 第 $attemptIndex 次生成"
                            accepted -> "已采纳 · 首次生成"
                            attemptIndex > 1 -> "未采纳 · 第 $attemptIndex 次生成"
                            else -> "未采纳"
                        },
                    )
                    reason?.takeIf { !accepted && it.isNotBlank() }?.let {
                        InfoRow(label = "未采纳原因", value = it)
                    }
                    if (diagnostics.isNotEmpty()) {
                        InfoRow(label = "诊断", value = diagnostics.joinToString("\n"))
                    }
                    InfoRow(
                        label = "模型",
                        value = buildString {
                            append(model ?: "未记录（旧版本）")
                            if (!slotName.isNullOrBlank()) append(" · 来自「$slotName」")
                        },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = buildString {
                                append("参考（${effective.size}）")
                                if (sampling) append(" · 正在取样…")
                            },
                            style = MiuixTheme.textStyles.subtitle,
                            color = colorScheme.onSurface,
                        )
                        val loaded = icons
                        when {
                            loaded == null -> Text(
                                text = "正在渲染采样图…",
                                style = MiuixTheme.textStyles.footnote1,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            loaded.isEmpty() -> Text(
                                text = "采样图不可用（图标包或应用已不在本机）",
                                style = MiuixTheme.textStyles.footnote1,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                            else -> loaded.forEachIndexed { index, pair ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Tile(loadBytes = { pair.original }, fallbackLabel = "原图", size = 44)
                                    Text(
                                        text = "→",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                    Tile(loadBytes = { pair.pack }, fallbackLabel = "包内", size = 44)
                                    Text(
                                        text = effective.getOrNull(index)?.label
                                            ?: effective.getOrNull(index)?.packageName.orEmpty(),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = colorScheme.onSurfaceVariantSummary,
                                        maxLines = 2,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "提示词",
                            style = MiuixTheme.textStyles.subtitle,
                            color = colorScheme.onSurface,
                        )
                        Card(
                            colors = CardDefaults.defaultColors(color = colorScheme.surfaceContainerHigh),
                            cornerRadius = 12.dp,
                        ) {
                            Text(
                                text = prompt?.takeIf { it.isNotBlank() } ?: "未记录（旧版本记录只保留了模型与参考）",
                                modifier = Modifier
                                    .padding(10.dp)
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                                style = MiuixTheme.textStyles.footnote1,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = if (sampling) "取样中…" else "重新取样",
                            onClick = {
                                if (sampling) return@TextButton
                                sampling = true
                                scope.launch {
                                    val next = runCatching { onSample() }.getOrNull()
                                    if (next != null) sampled = next
                                    sampling = false
                                }
                            },
                            enabled = !busy && !sampling,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { picking = true },
                            enabled = !busy && !sampling && slots.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "重新生成")
                        }
                    }
                } else {
                    Text(
                        text = "请求模型",
                        style = MiuixTheme.textStyles.subtitle,
                        color = colorScheme.onSurface,
                    )
                    if (slots.isEmpty()) {
                        Text(
                            text = "没有可用的供应商，请先在设置里添加。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.error,
                        )
                    } else {
                        // 就地单选列表：弹窗内不能再挂 OverlayDropdownPopup
                        // （列表会渲染到根 Scaffold、被平台 Dialog 挡住），直接列出所有槽位
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            slots.forEachIndexed { index, slot ->
                                RadioButtonPreference(
                                    title = slot.name,
                                    summary = slot.model,
                                    selected = index == selectedIndex,
                                    onClick = { selectedIndex = index },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = "返回",
                            onClick = { picking = false },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                slots.getOrNull(selectedIndex)?.let { onRegenerate(it.id, sampled?.references) }
                            },
                            enabled = !busy && slots.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = if (busy) "请求中…" else "请求生成")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.subtitle,
            color = colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}
