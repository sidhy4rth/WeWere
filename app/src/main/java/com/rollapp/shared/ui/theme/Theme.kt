package com.rollapp.shared.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Coral40,
    onSecondary = Color.White,
    secondaryContainer = Coral80,
    onSecondaryContainer = Color(0xFF40000A),
    background = Sand95,
    onBackground = Grey20,
    surface = Sand95,
    onSurface = Grey20,
    surfaceVariant = Sand90,
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE7E0EC),
    error = Error40,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    secondary = Coral80,
    onSecondary = Color(0xFF5F1120),
    secondaryContainer = Color(0xFF7E2A38),
    onSecondaryContainer = Coral80,
    background = Grey10,
    onBackground = Grey90,
    surface = Grey10,
    onSurface = Grey90,
    surfaceVariant = Grey30,
    onSurfaceVariant = Grey60,
    outline = Color(0xFF48454E),
    outlineVariant = Grey30,
    error = Error80,
    onError = Color(0xFF690005)
)

/** Generous radii throughout — this is a consumer app, not a dashboard. */
private val RollShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun RollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You wallpaper colours, where the device offers them. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RollTypography,
        shapes = RollShapes,
        content = content
    )
}
