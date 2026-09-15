package dev.artplus.iconpackfiller.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.artplus.iconpackfiller.provider.ModelVendor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 厂商徽标：有真图标（`ModelVendor.iconRes`）时显示厂商 logo，
 * 否则回退纯 Compose 绘制的圆形字母徽。
 *
 * 真图标为 LobeHub Icons SVG 转制的 VectorDrawable（见 `THIRD-PARTY-NOTICES.md`）；
 * 单色图标（[ModelVendor.tintIcon]）按主题 onSurface 着色，适配深浅色。
 *
 * 用在模型选择器的文本与 Radio 之间，一眼看出模型归属。
 */
@Composable
fun VendorBadge(
    vendor: ModelVendor,
    modifier: Modifier = Modifier,
) {
    val res = vendor.iconRes
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = vendor.label,
            colorFilter = if (vendor.tintIcon) {
                ColorFilter.tint(colorScheme.onSurface)
            } else {
                null
            },
            modifier = modifier.size(32.dp),
        )
        return
    }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(vendor.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = vendor.shortText,
            color = Color.White,
            fontSize = if (vendor.shortText.length > 1) 11.sp else 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
