package com.kitefoldlabs.bridle.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kitefoldlabs.bridle.R

/** Space Grotesk: everything people read. */
val SansFamily: FontFamily =
    FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )

/**
 * JetBrains Mono: everything the machine generates (keys, fingerprints, commands, file contents),
 * so it reads as distinct from Bridle's own copy.
 */
val MonoFamily: FontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )

private fun style(
    size: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
    lineHeight: Double? = null,
) = TextStyle(
    fontFamily = SansFamily,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
)

/** Tight negative tracking on headings, 1.5 line height on body text. */
internal val BridleTypography: Typography =
    Typography(
        displayLarge = style(44, FontWeight.SemiBold, -1.8, 1.05),
        displayMedium = style(36, FontWeight.SemiBold, -1.4, 1.1),
        displaySmall = style(32, FontWeight.SemiBold, -1.2, 1.1),
        headlineLarge = style(30, FontWeight.SemiBold, -1.0, 1.1),
        headlineMedium = style(24, FontWeight.SemiBold, -0.6, 1.15),
        headlineSmall = style(22, FontWeight.SemiBold, -0.5, 1.2),
        titleLarge = style(20, FontWeight.SemiBold, -0.4, 1.2),
        titleMedium = style(16, FontWeight.Medium, -0.2, 1.3),
        titleSmall = style(14, FontWeight.Medium, 0.0, 1.3),
        bodyLarge = style(16, FontWeight.Normal, 0.0, 1.5),
        bodyMedium = style(14, FontWeight.Normal, 0.0, 1.5),
        bodySmall = style(13, FontWeight.Normal, 0.0, 1.45),
        labelLarge = style(14, FontWeight.Medium),
        labelMedium = style(12, FontWeight.Medium, 0.2),
        labelSmall = style(11, FontWeight.Medium, 0.2),
    )

/** Small radii: chips 4, controls 6, cards 8, large containers 12, sheets and dialogs 16. */
internal val BridleShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp),
    )
