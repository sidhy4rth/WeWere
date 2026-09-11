package com.rollapp.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.GoldBrush
import com.rollapp.shared.ui.theme.HairlineBrush
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.Muted
import com.rollapp.shared.ui.theme.OnGold

/** The one easing every entrance in the app shares. Fast out, long settle. */
val Settle = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Fades and lifts [content] into place once, the first time it is composed.
 *
 * Every list, header and button arrives this way, staggered by [delayMillis], so a
 * screen is built in front of the user rather than snapped on. The state lives in a
 * remembered Animatable so a recomposition mid-flight does not restart it.
 */
@Composable
fun RiseIn(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    distance: Dp = 18.dp,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 700, delayMillis = delayMillis, easing = Settle))
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * distance.toPx()
        }
    ) { content() }
}

/** A fading gold rule. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HairlineBrush)
    )
}

/**
 * Mono, upper-case, tracked — the camera-readout voice used for counters,
 * timestamps and section eyebrows. Never for sentences.
 */
@Composable
fun Readout(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Muted,
    textAlign: TextAlign? = null
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * The primary action: a brushed-gold pill with a soft glow. One per screen at most.
 */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(
                elevation = if (enabled) 18.dp else 0.dp,
                shape = CircleShape,
                ambientColor = Gold.copy(alpha = 0.35f),
                spotColor = Gold.copy(alpha = 0.45f)
            )
            .clip(CircleShape)
            .background(GoldBrush)
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interaction,
                indication = ripple(color = Color.Black),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
                color = OnGold
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = OnGold, modifier = Modifier.size(20.dp))
                }
                Text(text, style = MaterialTheme.typography.labelLarge, color = OnGold)
            }
        }
    }
}

/** The secondary action: same pill, a gold hairline instead of a fill. */
@Composable
fun HairlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .border(1.dp, Gold.copy(alpha = 0.45f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Ivory, modifier = Modifier.size(20.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = Ivory)
        }
    }
}

/** A quiet tertiary action — plain text, muted, generous hit target. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Muted
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** A filter chip: filled gold when on, hairline when off. */
@Composable
fun GoldChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(Gold)
                else Modifier.border(1.dp, Gold.copy(alpha = 0.5f), CircleShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) OnGold else Gold,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) OnGold else Ivory
        )
    }
}

/**
 * The shutter. A gold ring with a brushed fill and, when [pulsing], a ring that
 * expands and fades on a loop — the app's one always-moving element, so the eye
 * lands on the verb.
 */
@Composable
fun Shutter(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    pulsing: Boolean = true,
    contentDescription: String = "Take photo"
) {
    val transition = rememberInfiniteTransition(label = "shutter")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = Settle), RepeatMode.Restart),
        label = "pulse"
    )
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                if (pulsing) {
                    val radius = (this.size.minDimension / 2f) * (1f + 0.5f * pulse)
                    drawCircle(
                        color = Gold.copy(alpha = 0.7f * (1f - pulse)),
                        radius = radius,
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
            .clip(CircleShape)
            .border(3.dp, Gold, CircleShape)
            .clickable(
                onClick = onClick,
                onClickLabel = contentDescription,
                indication = ripple(bounded = false, color = Gold),
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size - 16.dp)
                .shadow(12.dp, CircleShape, spotColor = Gold.copy(alpha = 0.5f))
                .clip(CircleShape)
                .background(GoldBrush)
        )
    }
}
