package dev.artplus.iconpackfiller.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.Phase
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 步骤 3：生成 + 打包进行中。标题随 [Phase] 在"生成中/打包中"间切换。
 *
 * 取消按钮为显式操作，直接取消；返回手势/返回键由 App 层弹确认框。
 */
@Composable
fun ProgressScreen(
    state: UiState,
    viewModel: MainViewModel,
    onCancelNow: () -> Unit,
) {
    MiuixScreen(
        title = state.progressTitle,
    ) {
        item(key = "error") {
            ErrorCard(state.error)
        }

        item(key = "progress") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.statusText,
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface,
                    )
                    if (state.phase == Phase.PACKING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (state.totalToGenerate > 0) {
                        val target = state.generatedCount.toFloat() / state.totalToGenerate
                        val animated by animateFloatAsState(
                            targetValue = target,
                            label = "generationProgress",
                        )
                        LinearProgressIndicator(
                            progress = animated,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = "成功 ${state.generatedCount} / 失败 ${state.failedCount} / 共 ${state.totalToGenerate}",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        if (state.running) {
            item(key = "cancel") {
                Button(
                    onClick = onCancelNow,
                    colors = ButtonDefaults.buttonColors(
                        color = colorScheme.error,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .animateItem(),
                ) {
                    Text(text = "取消")
                }
            }
        }
    }
}
