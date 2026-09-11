package com.rollapp.shared.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Black and gold, always. There is no light theme: the app is a darkroom and the
 * photos are the only thing allowed to be bright. Chrome is warm near-black so it
 * sits behind skin tones and sunsets without turning them blue; gold is the single
 * accent and it is used sparingly — a hairline, a counter, one primary button.
 */

val Ink = Color(0xFF0B0A08)
val Surface = Color(0xFF141210)
val Raised = Color(0xFF1C1916)
val Hairline = Color(0x40D4AF37)

val Gold = Color(0xFFD4AF37)
val GoldLight = Color(0xFFE5C158)
val GoldDeep = Color(0xFFA8842A)
val OnGold = Color(0xFF14110A)

val Ivory = Color(0xFFF3EDE0)
val IvoryMuted = Color(0xFFB9B0A0)
val Muted = Color(0xFF8E8578)

val Error = Color(0xFFFFB4AB)
val OnError = Color(0xFF690005)

/** Scrims for text laid over photographs. */
val PhotoScrimTop = Color(0x99000000)
val PhotoScrimBottom = Color(0xCC000000)

/** The primary-button and shutter fill: brushed gold, lit from above. */
val GoldBrush = Brush.verticalGradient(
    0f to GoldLight,
    0.55f to Gold,
    1f to GoldDeep
)

/** A fading gold rule — used under headers and as a divider. */
val HairlineBrush = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.5f to Gold.copy(alpha = 0.55f),
    1f to Color.Transparent
)
