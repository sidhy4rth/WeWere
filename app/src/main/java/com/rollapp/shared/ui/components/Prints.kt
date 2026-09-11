package com.rollapp.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rollapp.shared.ui.theme.GoldDeep
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory

/**
 * Three prints fanning out of a stack, the app's opening image. The photos are
 * abstract on purpose — nobody has signed in yet, so there is nothing real to show,
 * and a stock photo of strangers would say the wrong thing.
 */
@Composable
fun PrintStack(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.statusBarsPadding()) {
        // Positions are relative to the centre so the cluster holds together on any width.
        val cx = maxWidth / 2
        val cy = maxHeight / 2
        Print(
            brush = Brush.radialGradient(
                0f to Color(0xFF9AC6E8), 0.4f to Color(0xFF2F5F8A), 1f to Color(0xFF0D1B2A),
                center = Offset(0.8f, 0.2f), radius = 500f
            ),
            rotation = -11f,
            x = cx - PrintWidth / 2 - 82.dp, y = cy - PrintHeight / 2 - 20.dp,
            delayMillis = 80
        )
        Print(
            brush = Brush.radialGradient(
                0f to Color(0xFFF8E2A8), 0.3f to Color(0xFFD59A3F), 0.7f to Color(0xFF5A2D0C), 1f to Color(0xFF1A0C05),
                center = Offset(0.5f, 1f), radius = 500f
            ),
            rotation = 9f,
            x = cx - PrintWidth / 2 + 70.dp, y = cy - PrintHeight / 2 - 44.dp,
            delayMillis = 220
        )
        Print(
            brush = Brush.radialGradient(
                0f to Color(0xFFF2C27B), 0.35f to Color(0xFFB9642C), 1f to Color(0xFF2C1810),
                center = Offset(0.2f, 0.1f), radius = 520f
            ),
            rotation = -2f,
            x = cx - PrintWidth / 2 - 6.dp, y = cy - PrintHeight / 2 + 24.dp,
            delayMillis = 380
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Ink))
        )
    }
}

private val PrintWidth = 150.dp
private val PrintHeight = 190.dp

@Composable
private fun Print(
    brush: Brush,
    rotation: Float,
    x: Dp,
    y: Dp,
    delayMillis: Int
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(900, delayMillis = delayMillis, easing = Settle))
    }
    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .graphicsLayer {
                val p = progress.value
                alpha = p
                rotationZ = rotation * p
                translationY = (1f - p) * 40.dp.toPx()
                scaleX = 0.92f + 0.08f * p
                scaleY = scaleX
            }
            .size(width = PrintWidth, height = PrintHeight)
            .shadow(24.dp, RoundedCornerShape(6.dp), spotColor = Color.Black)
            .clip(RoundedCornerShape(6.dp))
            .background(Ivory)
            .padding(start = 7.dp, top = 7.dp, end = 7.dp, bottom = 28.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(brush)
        )
        Readout(
            text = "06 09 26",
            color = GoldDeep,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-4).dp, y = 19.dp)
        )
    }
}
