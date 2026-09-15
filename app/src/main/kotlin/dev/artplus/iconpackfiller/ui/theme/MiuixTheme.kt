package dev.artplus.iconpackfiller.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 明暗模式（外观的第一个维度）。
 */
enum class ThemeMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }

    fun label(): String = when (this) {
        SYSTEM -> "跟随系统"
        LIGHT -> "浅色"
        DARK -> "深色"
    }
}

/**
 * 应用内颜色模式 = [ThemeMode] × 莫奈取色开关。移植自 KernelSU-Style-UI-Kit（GPL-3.0-or-later，派生作品）。
 *
 * 存储值保持 0-5 单一整数：莫奈关闭为 0/1/2，开启为 3/4/5。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5);

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM

        /** 由明暗模式 + 莫奈开关组合出完整颜色模式。 */
        fun of(mode: ThemeMode, monet: Boolean): ColorMode = when (mode) {
            ThemeMode.SYSTEM -> if (monet) MONET_SYSTEM else SYSTEM
            ThemeMode.LIGHT -> if (monet) MONET_LIGHT else LIGHT
            ThemeMode.DARK -> if (monet) MONET_DARK else DARK
        }
    }

    val themeMode: ThemeMode
        get() = when (value % 3) {
            1 -> ThemeMode.LIGHT
            2 -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

    val monet: Boolean get() = value >= 3

    val isSystem: Boolean get() = value == 0 || value == 3
    val isDark: Boolean get() = value == 2 || value == 5
    val isMonet: Boolean get() = monet

    fun label(): String = when (this) {
        SYSTEM -> "跟随系统"
        LIGHT -> "浅色"
        DARK -> "深色"
        MONET_SYSTEM -> "跟随系统 + 莫奈"
        MONET_LIGHT -> "浅色 + 莫奈"
        MONET_DARK -> "深色 + 莫奈"
    }
}

/**
 * Miuix 主题包装。
 *
 * - 支持浅色/深色/莫奈取色
 * - 原生 keyColor 由 [keyColor] 指定（0 表示从壁纸取色）
 */
@Composable
fun FillerMiuixTheme(
    colorMode: ColorMode,
    keyColor: Int,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = colorMode.isDark || (colorMode.isSystem && systemDark)

    val controller = ThemeController(
        colorSchemeMode = when (colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK -> ColorSchemeMode.MonetDark
        },
        keyColor = if (keyColor == 0) null else Color(keyColor),
        isDark = darkTheme,
        paletteStyle = ThemePaletteStyle.TonalSpot,
        colorSpec = ThemeColorSpec.Spec2025,
    )

    MiuixTheme(
        controller = controller,
        content = {
            LaunchedEffect(darkTheme) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            CompositionLocalProvider(
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
            ) {
                content()
            }
        },
    )
}
