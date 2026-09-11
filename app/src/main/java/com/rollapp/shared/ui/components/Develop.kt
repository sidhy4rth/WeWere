package com.rollapp.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * An image that *develops*: it arrives blurred, desaturated and dim, and resolves to
 * the real photograph over about a second — the way a print comes up in the tray.
 *
 * The animation starts when the bitmap is ready, not when the composable appears, so
 * a slow network shows the placeholder, not a half-developed nothing. Blur needs
 * API 31; older devices get the saturation and brightness ramp alone.
 */
@Composable
fun DevelopingImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    durationMillis: Int = 1100,
    delayMillis: Int = 0
) {
    val progress = remember(model) { Animatable(0f) }
    var ready by remember(model) { mutableStateOf(false) }

    LaunchedEffect(ready) {
        if (ready) progress.animateTo(1f, tween(durationMillis, delayMillis, easing = Settle))
    }

    val p = progress.value
    val filter = remember(p) {
        ColorFilter.colorMatrix(
            ColorMatrix().apply {
                setToSaturation(0.2f + 0.8f * p)
                val b = 0.55f + 0.45f * p
                timesAssign(ColorMatrix(floatArrayOf(
                    b, 0f, 0f, 0f, 0f,
                    0f, b, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
        )
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(false)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        colorFilter = if (p < 1f) filter else null,
        onSuccess = { ready = true },
        modifier = modifier
            .graphicsLayer { alpha = 0.4f + 0.6f * p }
            .blur(((1f - p) * 10).dp)
    )
}
