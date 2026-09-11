package com.rollapp.shared.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rollapp.shared.core.Limits
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.TimelineItem
import com.rollapp.shared.domain.model.UploadState
import com.rollapp.shared.ui.components.EmptyState
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.UserAvatar

@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenPhoto: (String, String) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenMembers: (String) -> Unit,
    viewModel: GroupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_GALLERY_SELECTION)
    ) { uris -> viewModel.uploadFromGallery(uris) }

    // Fetch the next page a little before the user reaches the bottom, so the grid
    // never visibly stalls mid-scroll.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 12
        }
    }

    LaunchedEffect(shouldLoadMore, state.hasMore) {
        if (shouldLoadMore && state.hasMore) viewModel.loadOlder()
    }

    if (state.accessRevoked) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("You're no longer in this group") },
            text = { Text("An admin removed you, so its photos aren't available any more.") },
            confirmButton = { TextButton(onClick = onBack) { Text("OK") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.group?.name.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.group?.let { group ->
                            Text(
                                text = "${group.memberCount} ${if (group.memberCount == 1) "friend" else "friends"} · " +
                                    "${group.photoCount} ${if (group.photoCount == 1) "photo" else "photos"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier
                            .clickable { onOpenMembers(viewModel.groupId) }
                            .padding(horizontal = 6.dp)
                    ) {
                        state.members.take(3).forEachIndexed { index, member ->
                            UserAvatar(
                                name = member.name,
                                photoUrl = member.photoUrl,
                                seed = member.uid,
                                size = 26.dp,
                                borderColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.padding(start = if (index == 0) 0.dp else 0.dp)
                            )
                        }
                    }
                    IconButton(onClick = { onOpenSettings(viewModel.groupId) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Group settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FloatingActionButton(
                    onClick = {
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Upload from gallery")
                }
                Spacer(Modifier.width(14.dp))
                // The camera is the point of the screen, so it gets the larger target.
                FloatingActionButton(
                    onClick = { onOpenCamera(viewModel.groupId) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        Icons.Rounded.PhotoCamera,
                        contentDescription = "Take a photo",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (state.failedCount > 0) {
                InlineError(
                    message = "${state.failedCount} ${if (state.failedCount == 1) "photo" else "photos"} didn't upload",
                    actionLabel = "Retry",
                    onAction = viewModel::retryFailed,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (state.isEmpty) {
                EmptyState(
                    icon = Icons.Rounded.PhotoCamera,
                    title = "No photos yet",
                    body = "Take the first one — everyone in the group sees it straight away.",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (state.pendingUploads.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "uploading-header") {
                            SectionHeader("UPLOADING")
                        }
                        items(
                            items = state.pendingUploads,
                            key = { "pending-${it.id}" }
                        ) { upload ->
                            PendingTile(
                                progress = upload.progress,
                                isFailed = upload.state == UploadState.FAILED,
                                localUri = upload.localUri,
                                onCancel = { viewModel.cancelUpload(upload.id) }
                            )
                        }
                    }

                    state.timeline.forEach { entry ->
                        when (entry) {
                            is TimelineItem.Header -> item(
                                span = { GridItemSpan(maxLineSpan) },
                                key = "header-${entry.key}"
                            ) {
                                SectionHeader(entry.label)
                            }

                            is TimelineItem.Item -> item(key = entry.photo.id) {
                                PhotoTile(
                                    photo = entry.photo,
                                    onClick = { onOpenPhoto(viewModel.groupId, entry.photo.id) }
                                )
                            }
                        }
                    }

                    if (state.hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 8.dp)
    )
}

/**
 * Grid tiles load the thumbnail, never the full image. A group of a few hundred
 * photos would otherwise pull tens of megabytes to render a screen of 100px squares.
 */
@Composable
private fun PhotoTile(photo: Photo, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(photo.thumbnailUrl)
                .crossfade(true)
                .build(),
            contentDescription = photo.caption ?: "Photo by ${photo.uploaderName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (photo.totalReactions > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${photo.totalReactions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PendingTile(
    progress: Float,
    isFailed: Boolean,
    localUri: String,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = localUri,
            contentDescription = "Uploading",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            if (isFailed) {
                Text(
                    text = "Failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    strokeWidth = 3.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Cancel upload",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
