package dev.artplus.iconpackfiller.ui.screen

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
import dev.artplus.iconpackfiller.project.db.ProjectEntity
import dev.artplus.iconpackfiller.ui.MainViewModel
import dev.artplus.iconpackfiller.ui.UiState
import dev.artplus.iconpackfiller.ui.component.MiuixScreen
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
 * 项目列表：导入或选定图标包即产生一条项目，可点进查看对应表与生成历史。
 */
@Composable
fun ProjectsScreen(
    state: UiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    MiuixScreen(
        title = "项目",
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
        if (state.projects.isEmpty()) {
            item(key = "empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    BasicComponent(
                        title = "还没有项目",
                        summary = "导入或选定一个图标包即自动创建项目",
                    )
                }
            }
        } else {
            item(key = "title") {
                SmallTitle(text = "共 ${state.projects.size} 个项目", modifier = Modifier.padding(top = 8.dp))
            }
            items(state.projects, key = { it.id }) { project ->
                ProjectCard(project) { viewModel.openProject(project.id) }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        onClick = onClick,
        showIndication = true,
    ) {
        BasicComponent(
            title = project.packLabel,
            summary = buildString {
                append(project.packPackage)
                append(" · ")
                append(if (project.sourceKind == SourceKind.INSTALLED) "已安装" else "APK 文件")
                append(" · ")
                append(formatTime(project.updatedAt))
            },
            endActions = {
                Text(
                    text = "打开",
                    color = colorScheme.primary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
        )
    }
}
