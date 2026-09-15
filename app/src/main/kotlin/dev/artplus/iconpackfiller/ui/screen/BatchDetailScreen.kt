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
import dev.artplus.iconpackfiller.batch.BatchStatus
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.CompareRow
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import dev.artplus.iconpackfiller.ui.component.RegenerateDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * 批次详情：进度 + 每次请求的「原图 → 生成图」对比 + 导出。
 *
 * 图从批次目录惰性读取（见 [CompareRow]），批次多时也不会卡。
 */
@Composable
fun BatchDetailScreen(
    state: UiState,
    viewModel: MainViewModel,
    batchId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    // 详情以记录为准（列表里的对象可能已过期）；进行中的批次随 state 更新
    val batch = state.batchDetail?.takeIf { it.id == batchId } ?: viewModel.batchRecord(batchId)

    // 长按生成图 → 生成来源弹窗（重新取样 / 重新生成）
    val settings = remember { viewModel.settingsStore() }
    var regenTarget by remember { mutableStateOf<dev.artplus.iconpackfiller.batch.AttemptRecord?>(null) }
    val dialogSlots = remember(regenTarget, state.regenerating) {
        if (regenTarget != null) settings.providerSlots.slots else emptyList()
    }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME),
    ) { uri ->
        if (uri != null) {
            val file = viewModel.batchOutputApk(batchId, batch?.outputApk)
            if (file != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    MiuixScreen(
        title = batch?.packLabel ?: "任务详情",
        subtitle = batch?.packPackage.orEmpty(),
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
        if (batch == null) {
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
                        text = "${statusLabel(batch.status)} · ${formatTime(batch.createdAt)}",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface,
                    )
                    val animated by animateFloatAsState(targetValue = batch.progress, label = "batchProgress")
                    if (batch.status == BatchStatus.RUNNING) {
                        LinearProgressIndicator(
                            progress = animated,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = animated,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = "成功 ${batch.generatedCount} / 失败 ${batch.failedCount} / 共 ${batch.plannedCount}",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item(key = "export") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (batch.outputApk != null) {
                    Button(
                        onClick = { saver.launch(viewModel.batchFileName(batchId)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "导出 APK")
                    }
                }
                TextButton(
                    text = "删除记录",
                    onClick = {
                        viewModel.deleteBatch(batchId)
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (batch.attempts.isEmpty()) {
            item(key = "no-attempts") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = if (batch.status == BatchStatus.RUNNING) "等待第一次生成结果…" else "没有生成记录",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            item(key = "compare-title") {
                SmallTitle(
                    text = "生成对比（${batch.attempts.size}）",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(batch.attempts, key = { "${it.packageName}-${it.attempt}" }) { attempt ->
                val rowDiagnostics = batch.diagnostics.filter { line ->
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
                        viewModel.batchAttemptFile(batchId, attempt.sourceFile)?.readBytes()
                    },
                    loadGenerated = {
                        viewModel.batchAttemptFile(batchId, attempt.pngFile)?.readBytes()
                    },
                )
            }
        }

        // 已归属到对比行的诊断不再在底部重复；这里只剩批次级信息（标定、参考池、调用上限等）
        val attemptPrefixes = batch.attempts.map { "${it.packageName}:" }
        val orphanDiagnostics = batch.diagnostics.filterNot { line ->
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
            diagnostics = batch?.diagnostics.orEmpty().filter { line ->
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
            loadReferenceIcons = { snaps -> viewModel.referenceIcons(batchId, snaps) },
            onSample = {
                viewModel.sampleReferences(batchId, target.packageName, viewModel.referencePairCount)
            },
            onDismiss = {
                regenTarget = null
                viewModel.clearError()
            },
            onRegenerate = { slotId, refs ->
                regenTarget = null
                viewModel.regenerateAttempt(
                    batchId = batchId,
                    packageName = target.packageName,
                    attempt = target.attempt,
                    referencesOverride = refs,
                    slotId = slotId,
                )
            },
        )
    }
}

private fun statusLabel(status: BatchStatus): String = when (status) {
    BatchStatus.RUNNING -> "运行中"
    BatchStatus.COMPLETED -> "已完成"
    BatchStatus.CANCELLED -> "已取消"
    BatchStatus.INTERRUPTED -> "已中断（进程被杀）"
    BatchStatus.FAILED -> "执行失败"
}
