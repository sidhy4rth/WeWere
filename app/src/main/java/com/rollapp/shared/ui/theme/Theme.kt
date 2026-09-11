package com.rollapp.shared.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * One colour scheme, always dark. Dynamic colour is deliberately off: a wallpaper-
 * tinted app would break the black-and-gold identity on the first launch.
 */
private val Colors = darkColorScheme(
    primary = Gold,
    onPrimary = OnGold,
    primaryContainer = GoldDeep,
    onPrimaryContainer = Ivory,
    secondary = GoldLight,
    onSecondary = OnGold,
    secondaryContainer = Raised,
    onSecondaryContainer = Ivory,
    tertiary = IvoryMuted,
    onTertiary = Ink,
    background = Ink,
    onBackground = Ivory,
    surface = Ink,
    onSurface = Ivory,
    surfaceVariant = Raised,
    onSurfaceVariant = IvoryMuted,
    surfaceContainer = Surface,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = Raised,
    surfaceContainerLow = Surface,
    surfaceContainerLowest = Ink,
    inverseSurface = Ivory,
    inverseOnSurface = Ink,
    outline = Gold.copy(alpha = 0.45f),
    outlineVariant = Ivory.copy(alpha = 0.12f),
    error = Error,
    onError = OnError,
    scrim = Ink
)

/** Pills for anything tappable, soft corners for anything that holds a photo. */
private val RollShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RollTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = RollTypography,
        shapes = RollShapes,
        content = content
    )
}
