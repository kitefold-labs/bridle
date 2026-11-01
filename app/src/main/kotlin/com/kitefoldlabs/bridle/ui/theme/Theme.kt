package com.kitefoldlabs.bridle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode

private val Sky = Color(0xFF0F2A47)
private val Kite = Color(0xFFFFB547)

internal val LightColors: ColorScheme = lightColorScheme(primary = Sky, secondary = Kite)
internal val DarkColors: ColorScheme = darkColorScheme(primary = Kite, secondary = Color(0xFF9CC3F0))

@Composable
fun BridleTheme(
    mode: ThemeMode = ThemeMode.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM, ThemeMode.DYNAMIC -> isSystemInDarkTheme()
        }
    val colors =
        when {
            mode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            dark -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colors, content = content)
}
