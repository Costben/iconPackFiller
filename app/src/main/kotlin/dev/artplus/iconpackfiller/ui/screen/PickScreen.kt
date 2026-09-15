package dev.artplus.iconpackfiller.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.History
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 步骤 1：选择图标包（已安装列表 / 导入 APK）。
 */
@Composable
fun PickScreen(
    state: UiState,
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenBatches: () -> Unit,
) {
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectApk(uri, uri.lastPathSegment)
    }

    MiuixScreen(
        title = "图标包补全器",
        navigationIcon = {
            // 历史任务入口固定在左上角（环形箭头 + 表针的历史图标）
            IconButton(onClick = onOpenBatches) {
                Icon(
                    imageVector = MiuixIcons.History,
                    contentDescription = "历史任务",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        actions = {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Settings,
                    contentDescription = "设置",
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) {
        item(key = "error") {
            ErrorCard(state.error)
        }

        if (state.hasResult) {
            item(key = "result") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onClick = { viewModel.openResult() },
                    showIndication = true,
                ) {
                    BasicComponent(
                        title = state.packLabel ?: "上次生成结果",
                        summary = "点击查看并导出",
                    )
                }
            }
        }

        if (state.batches.isNotEmpty()) {
            item(key = "batches") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onClick = onOpenBatches,
                    showIndication = true,
                ) {
                    BasicComponent(
                        title = "历史任务",
                        summary = "共 ${state.batches.size} 个 · 查看进度与生成对比",
                    )
                }
            }
        }

        item(key = "installed-title") {
            SmallTitle(text = "已安装图标包", modifier = Modifier.padding(top = 8.dp))
        }

        if (state.packs.isEmpty()) {
            item(key = "empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onClick = { apkPicker.launch(arrayOf("*/*")) },
                    showIndication = true,
                ) {
                    BasicComponent(
                        title = "未发现已安装的图标包",
                        summary = "可以导入第三方图标包 APK 文件",
                    )
                }
            }
        } else {
            items(state.packs, key = { it.packageName }) { pack ->
                val selected = state.selectedPack?.packageName == pack.packageName
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .animateItem(),
                    onClick = { viewModel.selectPack(pack) },
                    showIndication = true,
                ) {
                    BasicComponent(
                        title = pack.label,
                        summary = pack.packageName,
                        startAction = {
                            PackIcon(pack.icon)
                        },
                        endActions = if (selected) {
                            {
                                Text(
                                    text = "已选择",
                                    color = colorScheme.primary,
                                    style = MiuixTheme.textStyles.body2,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        item(key = "import") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                onClick = { apkPicker.launch(arrayOf("*/*")) },
                showIndication = true,
            ) {
                BasicComponent(
                    title = "导入 APK 文件",
                    summary = state.selectedApkName ?: "从文件选择器导入第三方图标包",
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.UploadCloud,
                            contentDescription = null,
                            tint = colorScheme.onSurface,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(24.dp),
                        )
                    },
                )
            }
        }

        item(key = "scan") {
            Button(
                onClick = { viewModel.analyze() },
                enabled = !state.running && (state.selectedPack != null || state.selectedApkUri != null),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .animateItem(),
            ) {
                Text(text = if (state.running) "扫描中…" else "扫描应用")
            }
        }
    }
}

/**
 * 图标包应用图标（squircle 圆角，与 MIUI 图标观感一致）。
 */
@Composable
private fun PackIcon(bitmap: android.graphics.Bitmap?) {
    val imageBitmap = remember(bitmap) {
        bitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
    if (imageBitmap != null) {
        Icon(
            bitmap = imageBitmap,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(44.dp)
                .squircleClip(12.dp),
        )
    } else {
        Icon(
            imageVector = MiuixIcons.WorldClock,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(44.dp),
        )
    }
}
