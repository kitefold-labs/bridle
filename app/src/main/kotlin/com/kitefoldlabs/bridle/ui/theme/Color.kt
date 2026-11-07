package com.kitefoldlabs.bridle.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The Kitefold Labs palette, shared with Halyard: a near-black canvas with layered surfaces and one
// cyan accent for actions and selection. Light mode darkens the cyan to keep AA contrast on white.

/** Dark-first: the default look, and the canvas the app icon sits on. */
internal val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF26D2EE),
        onPrimary = Color(0xFF06222A),
        primaryContainer = Color(0xFF0A3B45),
        onPrimaryContainer = Color(0xFFB8F3FB),
        inversePrimary = Color(0xFF0C8FA8),
        secondary = Color(0xFF878E96),
        onSecondary = Color(0xFF0A0B0D),
        // The accent at ~16% over the canvas: selected tabs, tonal buttons.
        secondaryContainer = Color(0xFF143139),
        onSecondaryContainer = Color(0xFF26D2EE),
        tertiary = Color(0xFF34D399),
        onTertiary = Color(0xFF05231A),
        tertiaryContainer = Color(0xFF152B27),
        onTertiaryContainer = Color(0xFF34D399),
        error = Color(0xFFF87171),
        onError = Color(0xFF2A0A0A),
        errorContainer = Color(0xFF2E1E22),
        onErrorContainer = Color(0xFFFCA5A5),
        background = Color(0xFF0A0B0D),
        onBackground = Color(0xFFE9EBEE),
        surface = Color(0xFF0A0B0D),
        onSurface = Color(0xFFE9EBEE),
        surfaceVariant = Color(0xFF1B1F25),
        onSurfaceVariant = Color(0xFF878E96),
        // Flat: elevation comes from surface steps and hairlines, not an accent tint.
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFE9EBEE),
        inverseOnSurface = Color(0xFF101216),
        outline = Color(0xFF313234),
        outlineVariant = Color(0xFF202123),
        scrim = Color.Black,
        surfaceBright = Color(0xFF20252C),
        surfaceDim = Color(0xFF0A0B0D),
        surfaceContainerLowest = Color(0xFF0A0B0D),
        surfaceContainerLow = Color(0xFF101216),
        surfaceContainer = Color(0xFF15181D),
        surfaceContainerHigh = Color(0xFF1B1F25),
        surfaceContainerHighest = Color(0xFF20252C),
    )

/** Warm white canvas, white cards, cyan darkened for AA. */
internal val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF0C8FA8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB8F3FB),
        onPrimaryContainer = Color(0xFF053038),
        inversePrimary = Color(0xFF26D2EE),
        secondary = Color(0xFF5D646C),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDDF1F5),
        onSecondaryContainer = Color(0xFF075567),
        tertiary = Color(0xFF0C9F6E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE4F4EF),
        onTertiaryContainer = Color(0xFF06543A),
        error = Color(0xFFDC4D63),
        onError = Color.White,
        errorContainer = Color(0xFFFBEDEF),
        onErrorContainer = Color(0xFF8A1C2E),
        background = Color(0xFFFBFBF9),
        onBackground = Color(0xFF16181C),
        surface = Color(0xFFFBFBF9),
        onSurface = Color(0xFF16181C),
        surfaceVariant = Color(0xFFECECEA),
        onSurfaceVariant = Color(0xFF5D646C),
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF16181C),
        inverseOnSurface = Color(0xFFF4F4F1),
        outline = Color(0xFFCECECC),
        outlineVariant = Color(0xFFE4E4E2),
        scrim = Color.Black,
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFECECEA),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7F5),
        surfaceContainer = Color(0xFFF4F4F1),
        surfaceContainerHigh = Color(0xFFECECEA),
        surfaceContainerHighest = Color(0xFFE6E6E3),
    )

/**
 * State colours Material has no role for. Always paired with a label (text or TalkBack), never the
 * only signal. They stay on-brand under dynamic colour too.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val idle: Color,
)

internal val DarkStatus =
    StatusColors(success = Color(0xFF34D399), warning = Color(0xFFFBBF24), idle = Color(0xFF545B63))
internal val LightStatus =
    StatusColors(success = Color(0xFF0C9F6E), warning = Color(0xFFB87503), idle = Color(0xFF9AA0A7))

/** The current theme's [StatusColors]; [BridleTheme] provides it. */
val LocalStatusColors = staticCompositionLocalOf { DarkStatus }
