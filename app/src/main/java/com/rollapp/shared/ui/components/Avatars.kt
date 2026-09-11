package com.rollapp.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.absoluteValue

/**
 * Avatar with a deterministic colour fallback.
 *
 * The colour is derived from the user id, not picked at random, so the same person is
 * the same colour on every device and in every group — which is what makes an initials
 * bubble readable as a specific person rather than decoration.
 */
@Composable
fun UserAvatar(
    name: String,
    photoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    seed: String = name,
    borderColor: Color? = null
) {
    val initials = remember(name) {
        name.trim().split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }
    }

    val background = remember(seed) { avatarColorFor(seed) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(
                if (borderColor != null) Modifier.border(2.dp, borderColor, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Text(
                text = initials,
                color = Color(0xFF14110A),
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp
            )
        }
    }
}

/** The overlapping avatar row on a group card. */
@Composable
fun AvatarStack(
    photoUrls: List<String>,
    names: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    maxVisible: Int = 4,
    overlap: Dp = 9.dp,
    borderColor: Color = MaterialTheme.colorScheme.surface
) {
    val visible = photoUrls.take(maxVisible)
    Row(modifier = modifier) {
        visible.forEachIndexed { index, url ->
            UserAvatar(
                name = names.getOrNull(index) ?: "",
                photoUrl = url,
                size = size,
                seed = url,
                borderColor = borderColor,
                modifier = Modifier
                    .offset(x = -(overlap * index))
                    .zIndex((maxVisible - index).toFloat())
            )
        }
    }
}

/** Muted pastels: they have to sit next to gold without competing with it. */
private val AvatarColors = listOf(
    Color(0xFFF2C27B), Color(0xFFE8B4C9), Color(0xFF9AC6E8),
    Color(0xFF6F8F6A), Color(0xFFCFD7E2), Color(0xFFE8A87C),
    Color(0xFFB8A6D9), Color(0xFF8FBFB4)
)

internal fun avatarColorFor(seed: String): Color =
    AvatarColors[(seed.hashCode().absoluteValue) % AvatarColors.size]
