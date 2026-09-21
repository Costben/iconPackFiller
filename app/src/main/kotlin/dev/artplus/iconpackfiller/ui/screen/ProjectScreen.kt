package dev.artplus.iconpackfiller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.project.SourceKind
import dev.artplus.iconpackfiller.project.db.GenerationEntity
import dev.artplus.iconpackfiller.project.db.ProjectEntity
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.ErrorCard
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 项目详情：对应表概览 + 当前指向（`activeGenerationId`，可手动设置）+ 生成历史。
 */
@Composable
fun ProjectScreen(
    state: UiState,
    viewModel: MainViewModel,
    projectId: String,
    onBack: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val project = state.projects.firstOrNull { it.id == projectId }

    MiuixScreen(
        title = project?.packLabel ?: "项目",
        subtitle = project?.packPackage.orEmpty(),
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

        if (project == null) {
            item(key = "missing") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(
                        text = "项目不存在或已删除",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            return@MiuixScreen
        }

        item(key = "info") {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = project.packPackage,
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = "版本 ${project.packVersionCode} · " +
                            (if (project.sourceKind == SourceKind.INSTALLED) "已安装" else "APK 文件") +
                            " · 创建于 ${formatTime(project.createdAt)}",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "对应表 ${state.projectPackEntryCount} 条 · 源快照 ${project.sourceApkFile}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item(key = "snapshot-generate") {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                onClick = { viewModel.startGenerationFromProject(project.id) },
                showIndication = true,
            ) {
                BasicComponent(
                    title = "从项目快照生成",
                    summary = "源包已卸载或更新也可用：以项目内 source.apk 重新生成 / 重打包",
                )
            }
        }

        item(key = "active-title") {
            SmallTitle(text = "当前指向", modifier = Modifier.padding(top = 8.dp))
        }
        item(key = "active") {
            ActiveGenerationCard(
                project = project,
                generations = state.generations,
                onClear = { viewModel.setActiveGeneration(project.id, null) },
            )
        }

        item(key = "history-title") {
            SmallTitle(
                text = "生成历史（${state.generations.size}）",
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.generations.isEmpty()) {
            item(key = "no-generation") {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    BasicComponent(
                        title = "还没有生成记录",
                        summary = "从选择图标包开始跑一次生成",
                    )
                }
            }
        } else {
            items(state.generations, key = { it.id }) { generation ->
                GenerationCard(
                    generation = generation,
                    active = project.activeGenerationId == generation.id,
                    onClick = { viewModel.openGeneration(generation.id) },
                    onSetActive = { viewModel.setActiveGeneration(project.id, generation.id) },
                )
            }
        }

        item(key = "delete") {
            TextButton(
                text = "删除项目",
                onClick = {
                    viewModel.deleteProject(project.id)
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ActiveGenerationCard(
    project: ProjectEntity,
    generations: List<GenerationEntity>,
    onClear: () -> Unit,
) {
    val active = generations.firstOrNull { it.id == project.activeGenerationId }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (active == null) {
            BasicComponent(
                title = "未设置",
                summary = "生成完成后自动指向最新一次；也可在下方手动设置",
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${formatTime(active.createdAt)} · ${statusText(active.status)}",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = active.model ?: active.slotName ?: "生成 ${active.id}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                TextButton(text = "清除", onClick = onClear)
            }
        }
    }
}

@Composable
private fun GenerationCard(
    generation: GenerationEntity,
    active: Boolean,
    onClick: () -> Unit,
    onSetActive: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        onClick = onClick,
        showIndication = true,
    ) {
        BasicComponent(
            title = "${formatTime(generation.createdAt)} · ${statusText(generation.status)}",
            summary = buildString {
                if (generation.plannedCount > 0) {
                    append("成功 ${generation.generatedCount} / 失败 ${generation.failedCount} / 共 ${generation.plannedCount}")
                } else {
                    append(generation.model ?: generation.slotName ?: generation.id)
                }
            },
            endActions = {
                if (active) {
                    Text(
                        text = "当前",
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                } else {
                    TextButton(text = "设为当前", onClick = onSetActive)
                }
            },
        )
    }
}
