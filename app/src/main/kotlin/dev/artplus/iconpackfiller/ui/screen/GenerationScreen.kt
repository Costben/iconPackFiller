package dev.artplus.iconpackfiller.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.project.GenerationStatus
import dev.artplus.iconpackfiller.project.db.GenerationIconEntity
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.CompactDropdownPreference
import dev.artplus.iconpackfiller.ui.component.CompareRow
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import dev.artplus.iconpackfiller.ui.component.RegenerateDialog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val APK_MIME = "application/vnd.android.package-archive"

/** 筛选下拉里代表「不过滤」的哨兵项。 */
private const val FILTER_ALL = "全部"

/**
 * 生成详情：进度 + 按包名/组件/accepted 筛选本次图标 + 每次请求的原图→生成图对比。
 *
 * 点图标行进入 [dev.artplus.iconpackfiller.ui.navigation.Route.Compare]（同一目标跨生成并排看图）。
 * 图从生成目录惰性读取（见 [CompareRow]），生成多时也不会卡。
 */
@Composable
fun GenerationScreen(
    state: UiState,
    viewModel: MainViewModel,
    generationId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    // 详情以记录为准（列表里的对象可能已过期）；进行中的生成随 state 更新
    val record = state.batchDetail?.takeIf { it.id == generationId }
        ?: viewModel.batchRecord(generationId)

    val settings = remember { viewModel.settingsStore() }
    var regenTarget by remember { mutableStateOf<dev.artplus.iconpackfiller.project.AttemptRecord?>(null) }
    val dialogSlots = remember(regenTarget, state.regenerating) {
        if (regenTarget != null) settings.providerSlots.slots else emptyList()
    }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME),
    ) { uri ->
        if (uri != null) {
            val file = viewModel.batchOutputApk(generationId, record?.outputApk)
            if (file != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    MiuixScreen(
        title = record?.packLabel ?: "生成详情",
        subtitle = record?.packPackage.orEmpty(),
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
        if (record == null) {
            item(key = "missing") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        text = "记录不存在或已删除",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            return@MiuixScreen
        }

        item(key = "error") {
            ErrorCard(state.error)
        }

        item(key = "progress") {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "${statusLabel(record.status)} · ${formatTime(record.createdAt)}",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface,
                    )
                    val animated by animateFloatAsState(
                        targetValue = record.progress,
                        label = "generationProgress",
                    )
                    LinearProgressIndicator(
                        progress = animated,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "成功 ${record.generatedCount} / 失败 ${record.failedCount} / 共 ${record.plannedCount}",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (record.outputApk != null) {
                    Button(
                        onClick = { saver.launch(viewModel.batchFileName(generationId)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "导出 APK")
                    }
                }
                TextButton(
                    text = "设为当前",
                    onClick = { viewModel.setActiveGeneration(record.projectId, generationId) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "删除",
                    onClick = {
                        viewModel.deleteBatch(generationId)
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- 按包名 / 组件 / accepted 筛选本次图标 ----
        item(key = "filter-title") {
            SmallTitle(
                text = "本次图标（${state.generationIcons.size}/${state.allGenerationIcons.size}）",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(key = "filter") {
            FilterPanel(
                state = state,
                onPackage = viewModel::setIconPackageFilter,
                onActivity = viewModel::setIconActivityFilter,
                onAccepted = viewModel::setIconAcceptedFilter,
            )
        }

        if (state.generationIcons.isEmpty()) {
            item(key = "no-icons") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    BasicComponent(
                        title = "没有匹配的图标",
                        summary = if (state.allGenerationIcons.isEmpty()) {
                            "本次生成还没有图标记录"
                        } else {
                            "换个包名、组件或状态筛选"
                        },
                    )
                }
            }
        } else {
            items(state.generationIcons, key = { it.id }) { icon ->
                GenerationIconCard(icon) {
                    viewModel.openCompare(record.projectId, icon.packageName, icon.activityName)
                }
            }
        }

        // ---- 每次请求的原图 → 生成图对比（保留重新生成 / 重新取样） ----
        if (record.attempts.isEmpty()) {
            item(key = "no-attempts") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = if (record.status == GenerationStatus.RUNNING) "等待第一次生成结果…" else "没有生成记录",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            item(key = "compare-title") {
                SmallTitle(
                    text = "生成对比（${record.attempts.size}）",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(record.attempts, key = { "${it.packageName}-${it.attempt}" }) { attempt ->
                val rowDiagnostics = record.diagnostics.filter { line ->
                    line.startsWith("${attempt.packageName}:") &&
                        !(attempt.reason != null && line.endsWith(attempt.reason))
                }
                CompareRow(
                    title = attempt.label ?: attempt.packageName,
                    accepted = attempt.accepted,
                    reason = attempt.reason,
                    attemptIndex = attempt.attempt,
                    subtitle = attempt.packageName,
                    references = attempt.references,
                    diagnostics = rowDiagnostics,
                    regenerating = state.regenerating?.let {
                        it.packageName == attempt.packageName && it.attempt == attempt.attempt
                    } == true,
                    onShowDetails = { regenTarget = attempt },
                    loadSource = {
                        viewModel.batchAttemptFile(generationId, attempt.sourceFile)?.readBytes()
                    },
                    loadGenerated = {
                        viewModel.batchAttemptFile(generationId, attempt.pngFile)?.readBytes()
                    },
                )
            }
        }

        // 已归属到对比行的诊断不再在底部重复；这里只剩生成级信息（标定、参考池、调用上限等）
        val attemptPrefixes = record.attempts.map { "${it.packageName}:" }
        val orphanDiagnostics = record.diagnostics.filterNot { line ->
            attemptPrefixes.any { line.startsWith(it) }
        }
        if (orphanDiagnostics.isNotEmpty()) {
            item(key = "diagnostics-title") {
                SmallTitle(text = "诊断信息", modifier = Modifier.padding(top = 8.dp))
            }
            itemsIndexed(orphanDiagnostics, key = { index, _ -> "diag-$index" }) { _, line ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = line,
                        modifier = Modifier.padding(12.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }

    regenTarget?.let { target ->
        RegenerateDialog(
            title = target.label ?: target.packageName,
            accepted = target.accepted,
            attemptIndex = target.attempt,
            reason = target.reason,
            diagnostics = record?.diagnostics.orEmpty().filter { line ->
                line.startsWith("${target.packageName}:") &&
                    !(target.reason != null && line.endsWith(target.reason))
            },
            model = target.model,
            slotName = target.slotName,
            prompt = target.prompt,
            references = target.referenceDetails,
            referenceNames = target.references,
            slots = dialogSlots,
            initialSlotId = target.slotId,
            busy = state.regenerating != null,
            loadReferenceIcons = { snaps -> viewModel.referenceIcons(generationId, snaps) },
            onSample = {
                viewModel.sampleReferences(generationId, target.packageName, viewModel.referencePairCount)
            },
            onDismiss = {
                regenTarget = null
                viewModel.clearError()
            },
            onRegenerate = { slotId, refs ->
                regenTarget = null
                viewModel.regenerateAttempt(
                    batchId = generationId,
                    packageName = target.packageName,
                    attempt = target.attempt,
                    referencesOverride = refs,
                    slotId = slotId,
                )
            },
        )
    }
}

/**
 * 筛选面板：accepted（TabRow）+ 包名 / 组件（下拉，候选项来自本次全部图标行）。
 */
@Composable
private fun FilterPanel(
    state: UiState,
    onPackage: (String?) -> Unit,
    onActivity: (String?) -> Unit,
    onAccepted: (Boolean?) -> Unit,
) {
    val allIcons = state.allGenerationIcons
    val packageOptions = remember(allIcons) {
        listOf(FILTER_ALL) + allIcons.map { it.packageName }.distinct().sorted()
    }
    val packageIndex = state.iconFilter.packageName
        ?.let { packageOptions.indexOf(it) }
        ?.takeIf { it > 0 }
        ?: 0
    val activityOptions = remember(allIcons, state.iconFilter.packageName) {
        val base = allIcons
            .filter { state.iconFilter.packageName == null || it.packageName == state.iconFilter.packageName }
            .mapNotNull { it.activityName }
            .distinct()
            .sorted()
        listOf(FILTER_ALL) + base
    }
    val activityIndex = state.iconFilter.activityName
        ?.let { activityOptions.indexOf(it) }
        ?.takeIf { it > 0 }
        ?: 0
    val acceptedIndex = when (state.iconFilter.accepted) {
        null -> 0
        true -> 1
        false -> 2
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            TabRow(
                tabs = listOf("全部", "已采纳", "未采纳"),
                selectedTabIndex = acceptedIndex,
                onTabSelected = { index ->
                    onAccepted(
                        when (index) {
                            1 -> true
                            2 -> false
                            else -> null
                        },
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            CompactDropdownPreference(
                title = "包名",
                items = packageOptions,
                selectedIndex = packageIndex,
                onSelectedIndexChange = { index ->
                    onPackage(if (index == 0) null else packageOptions[index])
                },
            )
            CompactDropdownPreference(
                title = "组件",
                items = activityOptions,
                selectedIndex = activityIndex,
                enabled = state.iconFilter.packageName != null && activityOptions.size > 1,
                onSelectedIndexChange = { index ->
                    onActivity(if (index == 0) null else activityOptions[index])
                },
            )
        }
    }
}

@Composable
private fun GenerationIconCard(icon: GenerationIconEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        onClick = onClick,
        showIndication = true,
    ) {
        BasicComponent(
            title = icon.label ?: icon.packageName,
            summary = buildString {
                append(icon.packageName)
                icon.activityName?.let { append("/").append(it) }
                icon.drawableName?.let { append(" · ").append(it) }
                if (!icon.accepted) {
                    icon.reason?.let { append(" · ").append(it) }
                }
            },
            endActions = {
                Text(
                    text = if (icon.accepted) "已采纳" else "未采纳",
                    color = if (icon.accepted) colorScheme.primary else colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
        )
    }
}
