package com.rollapp.shared.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rollapp.shared.core.TimeFormat
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.ui.components.AvatarStack
import com.rollapp.shared.ui.components.EmptyState
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.UserAvatar
import com.rollapp.shared.ui.theme.PhotoScrimBottom

@Composable
fun HomeScreen(
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            if (state.groups.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onCreateGroup,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New group") }
                )
            }
        }
    ) { padding ->
        when {
            state.isEmpty -> EmptyHome(
                modifier = Modifier.padding(padding),
                onCreateGroup = onCreateGroup,
                onJoinGroup = onJoinGroup
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "header") {
                    HomeHeader(
                        name = state.user?.name?.substringBefore(" ").orEmpty(),
                        photoUrl = state.user?.photoUrl,
                        onOpenProfile = onOpenProfile
                    )
                }

                if (state.failedCount > 0) {
                    item(key = "failed-uploads") {
                        InlineError(
                            message = "${state.failedCount} ${plural(state.failedCount, "photo")} couldn't upload",
                            actionLabel = "Retry",
                            onAction = viewModel::retryFailedUploads,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else if (state.pendingCount > 0) {
                    item(key = "pending-uploads") {
                        PendingBanner(
                            count = state.pendingCount,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                item(key = "section-title") {
                    Text(
                        text = "Your groups",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )
                }

                items(state.groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        onClick = { onOpenGroup(group.id) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                item(key = "join") {
                    OutlinedButton(
                        onClick = onJoinGroup,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .height(50.dp)
                    ) {
                        Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Join with a code")
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
    onOpenProfile: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (name.isBlank()) "Hey 👋" else "Hey $name 👋",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Your shared rolls",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        UserAvatar(
            name = name,
            photoUrl = photoUrl,
            size = 44.dp,
            modifier = Modifier.clickable(onClick = onOpenProfile)
        )
    }
}

/**
 * The card leads with the photograph. Name and counts sit on a scrim over the cover
 * rather than in a text block beneath it, so a list of groups reads as a stack of
 * memories instead of a list of rows.
 */
@Composable
private fun GroupCard(
    group: Group,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Scrim only across the lower third, so the photo stays the subject.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Transparent, PhotoScrimBottom),
                            startY = 0f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group.recentMemberPhotos.isNotEmpty()) {
                        AvatarStack(
                            photoUrls = group.recentMemberPhotos,
                            size = 24.dp,
                            borderColor = Color.White.copy(alpha = 0.85f)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = buildString {
                            append(group.memberCount)
                            append(if (group.memberCount == 1) " member" else " members")
                            append(" · ")
                            append(group.photoCount)
                            append(if (group.photoCount == 1) " photo" else " photos")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
                if (group.lastActivityAt > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = TimeFormat.relative(group.lastActivityAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingBanner(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$count ${plural(count, "photo")} waiting to upload",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun EmptyHome(
    modifier: Modifier = Modifier,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Rounded.Group,
            title = "No groups yet",
            body = "Start one for your next trip, or join a friend's with their code.",
            actionLabel = "Create a group",
            onAction = onCreateGroup,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = onJoinGroup,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .height(50.dp)
        ) {
            Text("I have an invite code")
        }
    }
}

internal fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"
