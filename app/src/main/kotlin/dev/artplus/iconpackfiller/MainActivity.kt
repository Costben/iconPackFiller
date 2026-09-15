package dev.artplus.iconpackfiller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.artplus.iconpackfiller.settings.SettingsStore
import dev.artplus.iconpackfiller.ui.App
import dev.artplus.iconpackfiller.ui.theme.ColorMode
import dev.artplus.iconpackfiller.ui.theme.FillerMiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { SettingsStore(applicationContext) }
            var colorMode by remember { mutableStateOf(ColorMode.fromValue(settings.colorMode)) }
            val darkMode = colorMode.isDark || (colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkMode },
                )
                onDispose { }
            }

            FillerMiuixTheme(
                colorMode = colorMode,
                keyColor = settings.keyColor,
            ) {
                App(
                    onColorModeChange = { mode ->
                        colorMode = mode
                        settings.colorMode = mode.value
                    },
                )
            }
        }
    }
}