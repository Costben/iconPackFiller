package dev.artplus.iconpackfiller.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.CompareRow
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import dev.artplus.iconpackfiller.ui.component.RegenerateDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val APK_MIME = "application/vnd.android.package-archive"


/**
 * 步骤 4：完成与导出。
 */
@Composable
fun DoneScreen(
    state: UiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    // 长按生成图 → 生成来源弹窗（与批次详情一致）
    val settings = remember { viewModel.settingsStore() }
    var regenTarget by remember { mutableStateOf<dev.artplus.iconpackfiller.project.AttemptRecord?>(null) }
    val dialogSlots = remember(regenTarget) {
        if (regenTarget != null) settings.providerSlots.slots else emptyList()
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME),
    ) { uri ->
        if (uri != null) {
            val file = viewModel.signedApk()
            if (file != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    MiuixScreen(
        title = "完成",
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
        item(key = "summary") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.packLabel ?: "补全包",
                        style = MiuixTheme.textStyles.headline1,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = "生成图标：${state.generatedCount}，失败：${state.failedCount}",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    state.outputApkPath?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        if (state.attempts.isNotEmpty()) {
            item(key = "attempts-title") {
                Text(
                    text = "生成对比（${state.attempts.size}）",
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(
                count = state.attempts.size,
                key = { index ->
                    val attempt = state.attempts[index]
                    "${attempt.packageName}-${attempt.attempt}"
                },
            ) { index ->
                val attempt = state.attempts[index]
                val batchRecord = state.batchDetail?.attempts?.firstOrNull {
                    it.packageName == attempt.packageName && it.attempt == attempt.attempt
                }
                CompareRow(
                    title = attempt.label ?: attempt.packageName,
                    accepted = attempt.accepted,
                    reason = attempt.reason,
                    attemptIndex = attempt.attempt,
                    subtitle = attempt.packageName,
                    references = attempt.references,
                    regenerating = state.regenerating?.let {
                        it.packageName == attempt.packageName && it.attempt == attempt.attempt
                    } == true,
                    onShowDetails = batchRecord?.let { { regenTarget = it } },
                    loadSource = { attempt.sourcePngBytes },
                    loadGenerated = { attempt.pngBytes },
                )
            }
        }

        item(key = "actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .animateItem(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "再做一个",
                    onClick = { viewModel.startOver() },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { saver.launch(viewModel.suggestedFileName()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "导出 APK")
                }
            }
        }
    }

    val batchId = state.batchDetail?.id
    regenTarget?.let { target ->
        RegenerateDialog(
            title = target.label ?: target.packageName,
            accepted = target.accepted,
            attemptIndex = target.attempt,
            reason = target.reason,
            diagnostics = emptyList(),
            model = target.model,
            slotName = target.slotName,
            prompt = target.prompt,
            references = target.referenceDetails,
            referenceNames = target.references,
            slots = dialogSlots,
            initialSlotId = target.slotId,
            busy = state.regenerating != null,
            loadReferenceIcons = { snaps ->
                batchId?.let { viewModel.referenceIcons(it, snaps) } ?: emptyList()
            },
            onSample = {
                batchId?.let {
                    viewModel.sampleReferences(it, target.packageName, viewModel.referencePairCount)
                }
            },
            onDismiss = {
                regenTarget = null
                viewModel.clearError()
            },
            onRegenerate = { slotId, refs ->
                regenTarget = null
                if (batchId != null) {
                    viewModel.regenerateAttempt(
                        batchId = batchId,
                        packageName = target.packageName,
                        attempt = target.attempt,
                        referencesOverride = refs,
                        slotId = slotId,
                    )
                }
            },
        )
    }
}
