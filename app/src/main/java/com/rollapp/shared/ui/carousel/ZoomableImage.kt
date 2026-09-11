package com.rollapp.shared.ui.carousel

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Pinch and double-tap zoom for one page of the carousel.
 *
 * The pager owns horizontal swiping, so this has to be careful about which gestures
 * it consumes. While zoomed out it consumes nothing horizontally and the pager keeps
 * working; once zoomed in, panning is consumed until the image hits its edge, at
 * which point further drag falls through and the pager takes over — the behaviour
 * that makes zoom and swipe coexist instead of fighting.
 *
 * [onZoomChanged] tells the parent whether vertical swipe-to-dismiss should still be
 * armed: dragging a zoomed-in photo means panning, not closing.
 */
@Composable
fun ZoomableImage(
    modifier: Modifier = Modifier,
    maxScale: Float = 4f,
    onTap: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val isZoomed = scale.value > 1.01f
    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    /** Keeps the image from being dragged off into empty space. */
    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        if (containerSize.width == 0) return Offset.Zero
        val maxX = (containerSize.width * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (containerSize.height * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                containerSize = size
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapPosition ->
                        scope.launch {
                            if (scale.value > 1.01f) {
                                offset = Offset.Zero
                                scale.animateTo(1f)
                            } else {
                                // Zoom toward the point the user tapped, not the centre.
                                val target = 2.5f
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                offset = clampOffset(
                                    (centre - tapPosition) * (target - 1f),
                                    target
                                )
                                scale.animateTo(target)
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                containerSize = size
                detectTransformGestures(
                    // Don't steal a pan until a pinch has actually begun; otherwise a
                    // fast horizontal flick never reaches the pager.
                    panZoomLock = true
                ) { _, pan, zoom, _ ->
                    val nextScale = (scale.value * zoom).coerceIn(1f, maxScale)
                    scope.launch { scale.snapTo(nextScale) }

                    offset = if (nextScale <= 1.01f) {
                        Offset.Zero
                    } else {
                        clampOffset(offset + pan, nextScale)
                    }
                }
            }
    ) {
        content(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
