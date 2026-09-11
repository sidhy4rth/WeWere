package com.rollapp.shared.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rollapp.shared.core.TimeFormat
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.ui.components.AvatarStack
import com.rollapp.shared.ui.components.GoldButton
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.PrintStack
import com.rollapp.shared.ui.components.QuietButton
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.components.RiseIn
import com.rollapp.shared.ui.components.Settle
import com.rollapp.shared.ui.components.UserAvatar
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.IvoryMuted
import com.rollapp.shared.ui.theme.Muted
import com.rollapp.shared.ui.theme.Raised
import com.rollapp.shared.ui.theme.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = Ink) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isEmpty -> EmptyHome(onCreateGroup = onCreateGroup, onJoinGroup = onJoinGroup)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(key = "header") {
                        HomeHeader(
                            name = state.user?.name.orEmpty(),
                            photoUrl = state.user?.photoUrl,
                            rollCount = state.groups.size,
                            onOpenProfile = onOpenProfile
                        )
                    }

                    if (state.failedCount > 0) {
                        item(key = "failed-uploads") {
                            InlineError(
                                message = "${state.failedCount} ${plural(state.failedCount, "photo")} couldn't upload",
                                actionLabel = "Retry",
                                onAction = viewModel::retryFailedUploads
                            )
                        }
                    } else if (state.pendingCount > 0) {
                        item(key = "pending-uploads") { PendingBanner(count = state.pendingCount) }
                    }

                    itemsIndexed(state.groups, key = { _, group -> group.id }) { index, group ->
                        RiseIn(delayMillis = 240 + (index.coerceAtMost(4) * 120), distance = 22.dp) {
                            RollCard(
                                group = group,
                                onClick = { onOpenGroup(group.id) }
                            )
                        }
                    }
                }
            }

            if (!state.isEmpty) {
                // The primary action floats over a fade so the list never hides behind it.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(0f to Color.Transparent, 0.55f to Ink)
                        )
                        .padding(top = 64.dp, start = 24.dp, end = 24.dp, bottom = 12.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RiseIn(delayMillis = 620) {
                        GoldButton(text = "New roll", icon = Icons.Rounded.Add, onClick = onCreateGroup)
                    }
                    RiseIn(delayMillis = 700) {
                        QuietButton(text = "I have an invite code", onClick = onJoinGroup)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    name: String,
    photoUrl: String?,
    rollCount: Int,
    onOpenProfile: () -> Unit
) {
    val today = remember { SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()) }
    Column(modifier = Modifier.statusBarsPadding()) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            RiseIn(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Readout(text = "$today · $rollCount ${plural(rollCount, "roll")}", color = Gold)
                    Text(
                        text = "Your rolls",
                        style = MaterialTheme.typography.displaySmall,
                        color = Ivory
                    )
                }
            }
            RiseIn(delayMillis = 120) {
                UserAvatar(
                    name = name,
                    photoUrl = photoUrl,
                    size = 44.dp,
                    borderColor = Gold.copy(alpha = 0.6f),
                    modifier = Modifier.clickable(onClick = onOpenProfile)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        DrawnHairline()
        Spacer(Modifier.height(4.dp))
    }
}

/** The header rule draws itself left to right, once. */
@Composable
private fun DrawnHairline() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(900, delayMillis = 200, easing = Settle)) }
    Hairline(
        modifier = Modifier.graphicsLayer {
            scaleX = progress.value
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        }
    )
}

/**
 * A group as a roll of film: the cover fills the frame, sprocket holes run down both
 * edges, and the name sits on the developed strip at the bottom. The photograph is
 * the whole card; everything else is printed on it.
 */
@Composable
private fun RollCard(
    group: Group,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(214.dp)
            .shadow(18.dp, MaterialTheme.shapes.large, spotColor = Color.Black)
            .clip(MaterialTheme.shapes.large)
            .background(Surface)
            .clickable(onClick = onClick)
    ) {
        if (!group.coverPhotoUrl.isNullOrBlank()) {
            AsyncImage(
                model = group.coverPhotoUrl,
                contentDescription = group.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to Raised, 1f to Surface,
                            center = Offset(0.3f, 0.2f), radius = 900f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = Gold.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Sprockets(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.copy(alpha = 0.05f),
                        0.45f to Ink.copy(alpha = 0.2f),
                        1f to Ink.copy(alpha = 0.92f)
                    )
                )
        )

        CornerMarks(modifier = Modifier.fillMaxSize())

        if (group.lastActivityAt > 0) {
            Row(
                modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .shadow(8.dp, CircleShape, spotColor = Gold, ambientColor = Gold)
                        .clip(CircleShape)
                        .background(Gold)
                )
                Readout(text = TimeFormat.relative(group.lastActivityAt), color = Ivory)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ivory,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Readout(
                    text = "${group.photoCount} ${plural(group.photoCount, "photo")} · " +
                        "${group.memberCount} ${plural(group.memberCount, "friend")}",
                    color = IvoryMuted
                )
            }
            if (group.recentMemberPhotos.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                AvatarStack(
                    photoUrls = group.recentMemberPhotos,
                    size = 28.dp,
                    overlap = 8.dp,
                    borderColor = Ink
                )
            }
        }
    }
}

/** Two columns of film sprocket holes, faint, along the card's edges. */
@Composable
private fun Sprockets(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val pitch = 16.dp.toPx()
        val radius = 2.5.dp.toPx()
        val inset = 7.dp.toPx()
        val color = Ivory.copy(alpha = 0.16f)
        var y = pitch / 2
        while (y < size.height) {
            drawCircle(color, radius, Offset(inset, y))
            drawCircle(color, radius, Offset(size.width - inset, y))
            y += pitch
        }
    }
}

/** Viewfinder corner brackets, top corners only — a hint, not a frame. */
@Composable
private fun CornerMarks(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val len = 18.dp.toPx()
        val stroke = 1.5.dp.toPx()
        val x0 = 22.dp.toPx()
        val y0 = 14.dp.toPx()
        val x1 = size.width - x0
        drawLine(Gold, Offset(x0, y0), Offset(x0 + len, y0), stroke)
        drawLine(Gold, Offset(x0, y0), Offset(x0, y0 + len), stroke)
        drawLine(Gold, Offset(x1, y0), Offset(x1 - len, y0), stroke)
        drawLine(Gold, Offset(x1, y0), Offset(x1, y0 + len), stroke)
    }
}

@Composable
private fun PendingBanner(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Raised)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Gold)
        )
        Spacer(Modifier.width(10.dp))
        Readout(text = "$count ${plural(count, "photo")} waiting to upload", color = IvoryMuted)
    }
}

@Composable
private fun EmptyHome(
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PrintStack(modifier = Modifier.fillMaxWidth().height(340.dp))
        Spacer(Modifier.weight(1f))
        RiseIn(delayMillis = 500) {
            Text(
                text = "Nothing on the roll yet",
                style = MaterialTheme.typography.headlineLarge,
                color = Ivory,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Spacer(Modifier.height(10.dp))
        RiseIn(delayMillis = 600) { Hairline(Modifier.width(120.dp)) }
        Spacer(Modifier.height(12.dp))
        RiseIn(delayMillis = 650) {
            Text(
                text = "Start one for your next trip, or join a friend's with their code.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        RiseIn(delayMillis = 780) {
            GoldButton(text = "New roll", icon = Icons.Rounded.Add, onClick = onCreateGroup)
        }
        Spacer(Modifier.height(4.dp))
        RiseIn(delayMillis = 860) {
            QuietButton(text = "I have an invite code", onClick = onJoinGroup)
        }
        Spacer(Modifier.height(16.dp))
    }
}

internal fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"
