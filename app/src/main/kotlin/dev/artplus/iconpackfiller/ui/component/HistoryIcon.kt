package dev.artplus.iconpackfiller.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons

/**
 * 「历史记录」图标：环形回溯箭头 + 表针（与系统「历史记录」观感一致）。
 *
 * Miuix 图标集没有历史语义的图标（[MiuixIcons.Recent] 只有时钟、[MiuixIcons.Refresh] /
 * [MiuixIcons.Undo] 是纯箭头），这里内联 Material Icons 的 `history` 路径
 * （Apache-2.0，见 THIRD-PARTY-NOTICES.md），补上缺的语义。
 */
val MiuixIcons.History: ImageVector
    get() {
        _history?.let { return it }
        return ImageVector.Builder(
            name = "History",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(MATERIAL_HISTORY_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        ).build().also { _history = it }
    }

private var _history: ImageVector? = null

/** Material Icons `action/history` 24dp 路径（Apache-2.0）。 */
private const val MATERIAL_HISTORY_PATH =
    "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7" +
        "-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21" +
        "c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"
