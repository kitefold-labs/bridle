package com.kitefoldlabs.bridle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.kitefoldlabs.bridle.core.data.settings.ThemeMode

/**
 * Bridle's Material theme: the Kitefold Labs palette ([DarkColors], [LightColors]) or wallpaper
 * colours for [ThemeMode.DYNAMIC], with Space Grotesk type, small radii and [LocalStatusColors].
 */
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
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = BridleTypography, shapes = BridleShapes, content = content)
    }
}
