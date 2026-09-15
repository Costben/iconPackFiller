package dev.artplus.iconpackfiller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.artplus.iconpackfiller.ui.navigation.Route
import dev.artplus.iconpackfiller.ui.screen.BatchDetailScreen
import dev.artplus.iconpackfiller.ui.screen.BatchesScreen
import dev.artplus.iconpackfiller.ui.screen.DoneScreen
import dev.artplus.iconpackfiller.ui.screen.PickScreen
import dev.artplus.iconpackfiller.ui.screen.ProgressScreen
import dev.artplus.iconpackfiller.ui.screen.ReviewScreen
import dev.artplus.iconpackfiller.ui.screen.SettingsScreen
import dev.artplus.iconpackfiller.ui.theme.ColorMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 应用根：NavDisplay 页面转场（MIUI 默认参数）+ 全局取消确认框。
 */
@Composable
fun App(
    onColorModeChange: (ColorMode) -> Unit = {},
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshPacks()
    }

    // 任务在对话框显示期间结束时自动关闭（例如打包完成/失败）
    androidx.compose.runtime.LaunchedEffect(state.running) {
        if (!state.running) showCancelDialog = false
    }

    NavDisplay(
        backStack = viewModel.navigator.backStack,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        onBack = {
            // 运行中拦截返回：不 pop，弹确认框（转场自动回弹）
            if (state.running) {
                showCancelDialog = true
            } else {
                viewModel.navigator.pop()
            }
        },
        entryProvider = entryProvider {
            entry<Route.Pick> {
                PickScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = { viewModel.navigator.push(Route.Settings) },
                    onOpenBatches = { viewModel.openBatches() },
                )
            }
            entry<Route.Review> {
                ReviewScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { viewModel.navigator.pop() },
                )
            }
            entry<Route.Progress> {
                ProgressScreen(
                    state = state,
                    viewModel = viewModel,
                    // 显式按钮 = 直接取消（已明确表达意图，无需二次确认）
                    onCancelNow = { viewModel.cancel() },
                )
            }
            entry<Route.Done> {
                DoneScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { viewModel.navigator.pop() },
                )
            }
            entry<Route.Batches> {
                BatchesScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { viewModel.navigator.pop() },
                )
            }
            entry<Route.BatchDetail> { key ->
                BatchDetailScreen(
                    state = state,
                    viewModel = viewModel,
                    batchId = key.id,
                    onBack = { viewModel.navigator.pop() },
                )
            }
            entry<Route.Settings> {
                SettingsScreen(
                    viewModel = viewModel,
                    onColorModeChange = onColorModeChange,
                    onBack = { viewModel.navigator.pop() },
                )
            }
        },
    )

    CancelConfirmDialog(
        show = showCancelDialog,
        onConfirm = {
            showCancelDialog = false
            viewModel.cancel()
        },
        onDismiss = { showCancelDialog = false },
    )
}

/**
 * 生成中取消确认框。
 */
@Composable
private fun CancelConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = "取消生成？",
        summary = "已生成的图标会丢失，确定要取消吗？",
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = "继续生成",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = "取消生成",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
