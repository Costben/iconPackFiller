package dev.artplus.iconpackfiller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.project.db.GenerationIconMatch
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import dev.artplus.iconpackfiller.ui.component.Tile
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

/**
 * 跨生成对比：同一目标（包名 + 组件）在项目各次 Generation 里产出的图并排展示，新生成在前。
 */
@Composable
fun CompareScreen(
    state: UiState,
    viewModel: MainViewModel,
    projectId: String,
    packageName: String,
    activityName: String?,
    onBack: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val matches = state.compareMatches
    val title = matches.firstNotNullOfOrNull { it.icon.label } ?: packageName

    MiuixScreen(
        title = "跨生成对比",
        subtitle = activityName?.let { "$packageName/$it" } ?: packageName,
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

        item(key = "header") {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                BasicComponent(
                    title = title,
                    summary = "共 ${matches.size} 次生成产出该图标",
                )
            }
        }

        if (matches.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = "还没有可对比的生成记录",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            item(key = "list-title") {
                SmallTitle(
                    text = "生成序列（新 → 旧）",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(matches, key = { it.icon.id }) { match ->
                CompareGenerationRow(
                    match = match,
                    loadBytes = { viewModel.compareTargetIcon(projectId, match) },
                )
            }
        }
    }
}

@Composable
private fun CompareGenerationRow(
    match: GenerationIconMatch,
    loadBytes: suspend () -> ByteArray?,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, match.icon.id) {
        value = loadBytes()
    }
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tile(
                loadBytes = { bytes },
                fallbackLabel = if (match.icon.accepted) "生成" else "未采纳",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTime(match.generationCreatedAt),
                    style = MiuixTheme.textStyles.body1,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "${statusText(match.generationStatus)} · " +
                        (if (match.icon.accepted) "已采纳" else "未采纳"),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (match.icon.accepted) colorScheme.primary else colorScheme.error,
                )
                match.generationModel?.let { model ->
                    Text(
                        text = model,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
