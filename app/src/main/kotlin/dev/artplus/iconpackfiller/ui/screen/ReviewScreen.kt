package dev.artplus.iconpackfiller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.generate.TargetSelection
import dev.artplus.iconpackfiller.reference.ReferencePair
import dev.artplus.iconpackfiller.reference.ContactSheetComposer
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.CompactDropdownPreference
import dev.artplus.iconpackfiller.ui.component.ContactSheetPreview
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import dev.artplus.iconpackfiller.ui.component.ReferenceIconPair
import dev.artplus.iconpackfiller.ui.component.ReferencePickerDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 待生成应用默认最多显示多少个（其余点「展开更多」）。 */
private const val TARGET_PREVIEW_LIMIT = 10

/**
 * 步骤 2：确认覆盖率、勾选待生成应用。
 *
 * 默认全选；底部按钮只生成勾选的应用，避免一次性跑完整列表。
 */
@Composable
fun ReviewScreen(
    state: UiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current

    // 生成所用的供应商槽位（与设置页共用；选择即切换激活槽位）
    val settings = remember { viewModel.settingsStore() }
    var slots by remember { mutableStateOf(settings.providerSlots) }
    val keysConfigured = remember {
        settings.providerSlots.slots.associate { it.id to settings.loadApiKey(it.id).isNotBlank() }
    }

    // 参考图数量（上传给模型的参考对数）：在此调节，同时写回设置
    var pairCount by remember { mutableIntStateOf(settings.referencePairCount) }
    // 采样图刷新信号：重新抽样 / 选择应用后递增
    var sheetTick by remember { mutableIntStateOf(0) }
    var resampling by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<ReferencePair>?>(null) }
    var candidateIcons by remember { mutableStateOf<Map<String, ReferenceIconPair>>(emptyMap()) }
    var targetsExpanded by remember { mutableStateOf(false) }
    // 待生成应用搜索（应用名或包名，忽略大小写）
    val searchQuery = remember { TextFieldState() }
    val searchText = searchQuery.text.toString()
    val filteredTargets = remember(state.targets, searchText) {
        TargetSelection.filterByQuery(state.targets, searchText)
    }
    val scope = rememberCoroutineScope()

    // 打开「选择应用」时才构建候选池 + 缩略图（池与采样图共用缓存，通常已就绪）
    LaunchedEffect(pickerOpen) {
        if (pickerOpen) {
            candidates = null
            candidateIcons = emptyMap()
            val list = viewModel.referenceCandidates(state.report)
            candidates = list
            candidateIcons = viewModel.referencePreviews(list)
        }
    }

    MiuixScreen(
        title = "确认范围",
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
        item(key = "error") {
            ErrorCard(state.error)
        }

        item(key = "stats") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatRow("已覆盖", state.coveredCount.toString(), colorScheme.primary)
                    StatRow("待生成", state.uncoveredCount.toString(), colorScheme.onSurface)
                    StatRow("已排除", state.excludedCount.toString(), colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        val targets = state.targets
        if (targets.isNotEmpty()) {
            item(key = "list-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallTitle(
                        text = "待生成应用（已选 ${state.selectedCount}/${targets.size}）",
                        modifier = Modifier.weight(1f),
                    )
                    // 三态复选框代替「全选/取消全选」文字：
                    // 全选=On，部分选中=Indeterminate（横杠），未选=Off
                    Box(
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Checkbox(
                            state = when {
                                state.selectedCount == 0 -> ToggleableState.Off
                                state.allSelected -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = { viewModel.setAllTargetsSelected(!state.allSelected) },
                        )
                    }
                }
            }
            item(key = "search") {
                TextField(
                    state = searchQuery,
                    label = "搜索应用名或包名",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
            if (filteredTargets.isEmpty()) {
                item(key = "no-match") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    ) {
                        BasicComponent(
                            title = "没有匹配的应用",
                            summary = "换个应用名或包名试试",
                        )
                    }
                }
            } else {
                val shownTargets = if (targetsExpanded) {
                    filteredTargets
                } else {
                    filteredTargets.take(TARGET_PREVIEW_LIMIT)
                }
                items(shownTargets, key = { TargetSelection.keyOf(it) }) { app ->
                    val checked = state.selectedTargets == null ||
                        TargetSelection.keyOf(app) in state.selectedTargets
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .animateItem(),
                        onClick = { viewModel.toggleTarget(app) },
                        showIndication = true,
                    ) {
                        BasicComponent(
                            title = app.label ?: app.packageName,
                            summary = app.packageName,
                            endActions = {
                                Checkbox(
                                    state = ToggleableState(checked),
                                    // onClick = null：整个 Card 已是点击区域（含 Checkbox 位置），
                                    // 再挂一个回调会让点复选框时切换两次。
                                    onClick = null,
                                )
                            },
                        )
                    }
                }
                if (filteredTargets.size > TARGET_PREVIEW_LIMIT) {
                    item(key = "expand-targets") {
                        TextButton(
                            text = if (targetsExpanded) {
                                "收起"
                            } else {
                                "展开更多（还有 ${filteredTargets.size - TARGET_PREVIEW_LIMIT} 个）"
                            },
                            onClick = { targetsExpanded = !targetsExpanded },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        )
                    }
                }
            }
        } else {
            item(key = "empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    BasicComponent(
                        title = "没有需要生成的应用",
                        summary = "可直接打包原包",
                    )
                }
            }
        }

        item(key = "provider") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .animateItem(),
            ) {
                Column {
                    CompactDropdownPreference(
                        title = "生成模型",
                        summary = settings.apiProtocol.summaryLine,
                        items = slots.slots.map { slot ->
                            val suffix = if (keysConfigured[slot.id] == false) "（未配置 Key）" else ""
                            "${slot.name} · ${slot.model}$suffix"
                        },
                        selectedIndex = slots.slots.indexOfFirst { it.id == slots.activeId }.coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val target = slots.slots.getOrNull(index)
                                ?: return@CompactDropdownPreference
                            settings.setActiveSlot(target.id)
                            slots = settings.providerSlots
                        },
                    )
                    // 就地调节上传给模型的参考图数量（与设置页同一个值）
                    SliderPreference(
                        title = "参考图数量",
                        value = pairCount.toFloat(),
                        onValueChange = { value ->
                            val next = value.roundToInt()
                                .coerceIn(1, ContactSheetComposer.MAX_PAIRS)
                            if (next != pairCount) {
                                pairCount = next
                                settings.referencePairCount = next
                            }
                        },
                        valueRange = 1f..ContactSheetComposer.MAX_PAIRS.toFloat(),
                        steps = ContactSheetComposer.MAX_PAIRS - 2,
                        valueText = pairCount.toString(),
                    )
                    // 采样图：这一批会拿哪些参考对（左原图/右包内重绘）+ 目标应用原图
                    ContactSheetPreview(
                        pairCount = pairCount,
                        reloadTick = sheetTick,
                        loadSheet = { count ->
                            viewModel.batchReferenceSheet(
                                count = count,
                                reportOverride = state.report,
                                selectedTargets = state.selectedTargets,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = if (resampling) "抽样中…" else "重新抽样",
                            onClick = {
                                if (resampling) return@TextButton
                                resampling = true
                                scope.launch {
                                    viewModel.resampleBatchReferences(pairCount, state.report)
                                    sheetTick++
                                    resampling = false
                                }
                            },
                            enabled = !resampling,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "选择应用",
                            onClick = { pickerOpen = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item(key = "start") {
            Button(
                onClick = { viewModel.run() },
                enabled = state.selectedCount > 0,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .animateItem(),
            ) {
                Text(
                    text = if (state.selectedCount > 0) {
                        "生成选中（${state.selectedCount}）"
                    } else {
                        "请先勾选应用"
                    },
                )
            }
        }
    }

    // 「选择应用」：从图标包已覆盖的应用里任选参考；确认后整批固定使用
    ReferencePickerDialog(
        show = pickerOpen,
        candidates = candidates,
        maxCount = pairCount,
        initialSelected = state.batchReferences.mapTo(mutableSetOf()) { it.packageName },
        icons = candidateIcons,
        onConfirm = { picked ->
            viewModel.setBatchReferences(picked, pairCount)
            pickerOpen = false
            sheetTick++
        },
        onDismiss = { pickerOpen = false },
    )
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}
