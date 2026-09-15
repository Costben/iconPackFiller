package dev.artplus.iconpackfiller.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val TILE = 64

/**
 * 一条「原图 → 生成图」对比。
 *
 * 图通过 [loadSource] / [loadGenerated] 惰性解码：列表滚动时不把所有图都读进内存。
 * 两种数据来源共用本组件：完成页（内存字节）与批次详情页（磁盘文件）。
 *
 * 行内只保留状态文字（已采纳/未采纳），细节收进行尾 ⋯ 的详情弹窗
 * （[onShowDetails]；长按生成图同样触发），行高一致、方便点数。
 */
@Composable
fun CompareRow(
    title: String,
    accepted: Boolean,
    reason: String?,
    attemptIndex: Int,
    loadSource: () -> ByteArray?,
    loadGenerated: () -> ByteArray?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    references: List<String> = emptyList(),
    diagnostics: List<String> = emptyList(),
    regenerating: Boolean = false,
    onShowDetails: (() -> Unit)? = null,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tile(loadSource, fallbackLabel = "原图")
            Text(
                text = "→",
                style = MiuixTheme.textStyles.body1,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Tile(loadGenerated, fallbackLabel = "生成", onLongClick = onShowDetails)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = when {
                        regenerating -> "请求中…"
                        accepted -> if (attemptIndex > 1) "已采纳 · 第 $attemptIndex 次" else "已采纳"
                        else -> if (attemptIndex > 1) "未采纳 · 第 $attemptIndex 次" else "未采纳"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = when {
                        regenerating -> colorScheme.primary
                        accepted -> colorScheme.primary
                        else -> colorScheme.error
                    },
                )
            }
            IconButton(
                onClick = { onShowDetails?.invoke() },
                enabled = onShowDetails != null,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.More,
                    contentDescription = "生成详情",
                    tint = if (onShowDetails != null) {
                        colorScheme.onSurfaceVariantActions
                    } else {
                        colorScheme.disabledOnSecondaryVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 单张方形图；加载失败显示占位。
 *
 * [onLongClick] 非 null 时长按触发（生成图上的「重新请求」入口）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    loadBytes: () -> ByteArray?,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    size: Int = TILE,
    onLongClick: (() -> Unit)? = null,
) {
    // 以字节引用为 key：稳定来源（内存字节）不会因重组反复解码
    val bytes = loadBytes()
    val bitmap = remember(bytes) {
        bytes?.let {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
            }.getOrNull()
        }
    }
    val longPressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
    } else {
        Modifier
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(longPressModifier),
        )
    } else {
        Card(
            modifier = modifier.size(size.dp).then(longPressModifier),
            colors = CardDefaults.defaultColors(color = colorScheme.surfaceContainerHigh),
            cornerRadius = 14.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = fallbackLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
