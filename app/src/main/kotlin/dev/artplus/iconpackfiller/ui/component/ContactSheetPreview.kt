package dev.artplus.iconpackfiller.ui.component

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.artplus.iconpackfiller.reference.ContactSheetComposer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 「上传给模型的图片」采样图预览：按当前参考对数拼一张与生成时一致的 ContactSheet。
 *
 * 「确认范围」页与设置页共用：上排（或两排）是选中的参考对（左原图 / 右包内重绘），
 * 下方是目标应用原图——直接告诉用户这一批会拿哪些采样图当参考。
 *
 * 合成由 [loadSheet] 在 IO 线程执行；本组件只负责渲染、占位与「正在渲染」流光。
 */
@Composable
fun ContactSheetPreview(
    pairCount: Int,
    loadSheet: suspend (Int) -> ByteArray?,
    modifier: Modifier = Modifier,
    /** 递增此值强制重新加载（「重新抽样」「选择应用」后刷新采样图）。 */
    reloadTick: Int = 0,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 保留上一张成功图：重渲染时先模糊旧图 + 流光，别让用户对着空白等
        var display by remember { mutableStateOf<ByteArray?>(null) }
        var rendering by remember { mutableStateOf(true) }
        LaunchedEffect(pairCount, reloadTick) {
            rendering = true
            loadSheet(pairCount)?.let { display = it }
            rendering = false
        }
        val sheetBitmap = remember(display) {
            display?.let { bytes ->
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            if (sheetBitmap != null) {
                Image(
                    bitmap = sheetBitmap.asImageBitmap(),
                    contentDescription = "上传给模型的拼接图示例",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (rendering) Modifier.blur(12.dp) else Modifier),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!rendering) {
                        Text(
                            text = "示例不可用：没有已安装的图标包或可用的目标应用",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
            RenderingOverlay(active = rendering)
        }
        val rowsText = if (pairCount <= ContactSheetComposer.ROW_CAPACITY) {
            "上排 $pairCount 组参考对"
        } else {
            "两排共 $pairCount 组参考对（每排最多 ${ContactSheetComposer.ROW_CAPACITY} 组）"
        }
        Text(
            text = "请求时发给模型的拼接图：$rowsText（左原图 / 右包内重绘），下方是目标应用原图。",
            style = MiuixTheme.textStyles.footnote1,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 渲染中提示：斜向流光带 + 「正在渲染…」标签。
 *
 * 拼接图合成是纯本地工作（开包 → 渲染参考 → 裁边 → 排版），参考对变多时
 * 会有可见等待；用流动的光带告诉用户「在算，不是卡住」。
 */
@Composable
private fun BoxScope.RenderingOverlay(active: Boolean) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "sheet-render")
    val shift by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheet-render-shift",
    )
    Box(modifier = Modifier.matchParentSize()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val band = size.width * 0.4f
            val center = size.width * shift
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.5f),
                        Color.Transparent,
                    ),
                    start = Offset(center - band, 0f),
                    end = Offset(center + band, size.height),
                ),
            )
        }
        Card(
            modifier = Modifier.align(Alignment.Center),
            colors = CardDefaults.defaultColors(color = colorScheme.surfaceContainerHigh),
            cornerRadius = 10.dp,
        ) {
            Text(
                text = "正在渲染…",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = colorScheme.onSurface,
            )
        }
    }
}
