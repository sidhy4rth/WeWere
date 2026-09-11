package com.rollapp.shared.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rollapp.shared.R

/**
 * Three faces, each with one job.
 *
 * - **Cormorant Garamond** carries every title, and its italic carries dates,
 *   captions and the wordmark. It is what makes a group name read like the title of
 *   an album rather than a list row.
 * - **Manrope** is the body and button face — quiet on purpose, so the serif and
 *   the photographs get the room.
 * - **JetBrains Mono** is for readouts: counters, timestamps, "128 exposures". Set
 *   small, tracked wide and upper-case, it reads like the display on a camera.
 */
val Cormorant = FontFamily(
    Font(R.font.cormorant_500, FontWeight.Medium),
    Font(R.font.cormorant_600, FontWeight.SemiBold),
    Font(R.font.cormorant_500_italic, FontWeight.Medium, FontStyle.Italic)
)

val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold)
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_500, FontWeight.Medium)
)

val RollTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Cormorant,
        fontWeight = FontWeight.Medium,
        fontStyle = FontStyle.Italic,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.2).sp
    ),
    displaySmall = TextStyle(
        fontFamily = Cormorant,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Cormorant,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Cormorant,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Cormorant,
        fontWeight = FontWeight.Medium,
        fontStyle = FontStyle.Italic,
        fontSize = 22.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Cormorant,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp
    )
)
