package com.rollapp.shared.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rollapp.shared.core.Limits
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.PhotoFilter
import com.rollapp.shared.domain.model.PhotoFilterCodec
import com.rollapp.shared.domain.model.TimelineItem
import com.rollapp.shared.domain.model.UploadState
import com.rollapp.shared.ui.components.EmptyState
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.UserAvatar
import kotlinx.coroutines.launch

@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenPhoto: (String, String, String) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenMembers: (String) -> Unit,
    onOpenSlideshow: (String, String) -> Unit,
    viewModel: GroupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var confirmBulkDelete by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_GALLERY_SELECTION)
    ) { uris -> viewModel.uploadFromGallery(uris) }

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

    // Leaving selection mode is what Back should do first, before leaving the group.
    androidx.activity.compose.BackHandler(enabled = state.isSelecting) {
        viewModel.clearSelection()
    }

    if (state.accessRevoked) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("You're no longer in this group") },
            text = { Text("An admin removed you, so its photos aren't available any more.") },
            confirmButton = { TextButton(onClick = onBack) { Text("OK") } }
        )
    }

    if (confirmBulkDelete) {
        val deletable = state.deletableCount()
        val skipped = state.selectedIds.size - deletable

        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete $deletable ${if (deletable == 1) "photo" else "photos"}?") },
            text = {
                Text(
                    buildString {
                        append("They're removed for everyone in the group and can't be recovered.")
                        // Be explicit rather than silently dropping part of the selection.
                        if (skipped > 0) {
                            append("\n\n$skipped of your selection ")
                            append(if (skipped == 1) "was" else "were")
                            append(" uploaded by someone else and will be left alone.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    viewModel.deleteSelection { count ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Deleted $count ${if (count == 1) "photo" else "photos"}"
                            )
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.isSelecting) {
                SelectionTopBar(
                    count = state.selectedIds.size,
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onSave = {
                        viewModel.saveSelectionToDevice { count ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Saved $count ${if (count == 1) "photo" else "photos"} to your gallery"
                                )
                            }
                        }
                    },
                    onDelete = { confirmBulkDelete = true },
                    deletableCount = state.deletableCount()
                )
            } else {
                GroupTopBar(
                    state = state,
                    onBack = onBack,
                    onOpenMembers = { onOpenMembers(viewModel.groupId) },
                    onOpenSettings = { onOpenSettings(viewModel.groupId) },
                    onOpenSlideshow = {
                        onOpenSlideshow(viewModel.groupId, PhotoFilterCodec.encode(state.filter))
                    }
                )
            }
        },
        floatingActionButton = {
            // The camera has no place in selection mode; the action bar owns the screen.
            AnimatedVisibility(
                visible = !state.isSelecting,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingActionButton(
                        onClick = {
                            galleryPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp)
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Upload from gallery")
                    }
                    Spacer(Modifier.width(14.dp))
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
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

            state.bulkProgress?.let { progress ->
                BulkProgressBar(progress)
            }

            if (state.failedCount > 0) {
                InlineError(
                    message = "${state.failedCount} ${if (state.failedCount == 1) "photo" else "photos"} didn't upload",
                    actionLabel = "Retry",
                    onAction = viewModel::retryFailed,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (!state.isSelecting) {
                FilterRow(
                    state = state,
                    onSelect = viewModel::setFilter
                )
            }

            when {
                state.isEmpty && state.filter.isActive -> EmptyState(
                    icon = Icons.Rounded.Star,
                    title = emptyFilterTitle(state.filter),
                    body = "Nothing here yet. Clear the filter to see the whole roll.",
                    actionLabel = "Show all photos",
                    onAction = viewModel::clearFilter,
                    modifier = Modifier.weight(1f)
                )

                state.isEmpty -> EmptyState(
                    icon = Icons.Rounded.PhotoCamera,
                    title = "No photos yet",
                    body = "Take the first one — everyone in the group sees it straight away.",
                    modifier = Modifier.weight(1f)
                )

                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (state.pendingUploads.isNotEmpty() && !state.filter.isActive) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "uploading-header") {
                            SectionHeader("UPLOADING")
                        }
                        items(state.pendingUploads, key = { "pending-${it.id}" }) { upload ->
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
                                val photo = entry.photo
                                PhotoTile(
                                    photo = photo,
                                    isSelected = photo.id in state.selectedIds,
                                    isSelecting = state.isSelecting,
                                    isFavorite = photo.isFavoritedBy(state.myUid),
                                    onClick = {
                                        if (state.isSelecting) {
                                            viewModel.toggleSelection(photo.id)
                                        } else {
                                            onOpenPhoto(
                                                viewModel.groupId,
                                                photo.id,
                                                PhotoFilterCodec.encode(state.filter)
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(photo.id)
                                    }
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
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTopBar(
    state: GroupUiState,
    onBack: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSlideshow: () -> Unit
) {
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
            if (state.photos.isNotEmpty()) {
                IconButton(onClick = onOpenSlideshow) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play slideshow"
                    )
                }
            }
            IconButton(onClick = onOpenMembers) {
                Icon(Icons.Rounded.People, contentDescription = "Members")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Group settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun SelectionTopBar(
    count: Int,
    deletableCount: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text("$count selected", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Rounded.SelectAll, contentDescription = "Select all")
            }
            IconButton(onClick = onSave) {
                Icon(Icons.Rounded.Download, contentDescription = "Save to device")
            }
            // Hidden rather than disabled when none of the selection is yours: a
            // greyed-out bin invites a tap that can never work.
            if (deletableCount > 0) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/**
 * Filters are people, not tags. On a trip the question is almost always "what did
 * Priya get?" or "which ones did I star?", so those are the only two axes offered.
 */
@Composable
private fun FilterRow(
    state: GroupUiState,
    onSelect: (PhotoFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = state.filter is PhotoFilter.All,
            onClick = { onSelect(PhotoFilter.All) },
            label = { Text("All") }
        )

        FilterChip(
            selected = state.filter is PhotoFilter.Favorites,
            onClick = { onSelect(PhotoFilter.Favorites) },
            label = { Text("Starred") },
            leadingIcon = {
                Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )

        state.members.forEach { member ->
            val selected = (state.filter as? PhotoFilter.ByUploader)?.uid == member.uid
            FilterChip(
                selected = selected,
                onClick = {
                    onSelect(
                        if (selected) PhotoFilter.All
                        else PhotoFilter.ByUploader(member.uid, member.name)
                    )
                },
                label = {
                    Text(if (member.uid == state.myUid) "You" else member.name.substringBefore(" "))
                },
                leadingIcon = {
                    UserAvatar(
                        name = member.name,
                        photoUrl = member.photoUrl,
                        seed = member.uid,
                        size = 18.dp
                    )
                },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}

@Composable
private fun BulkProgressBar(progress: BulkProgress) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = when (progress.action) {
                BulkAction.SAVING -> "Saving ${progress.done} of ${progress.total} to your gallery"
                BulkAction.DELETING -> "Deleting ${progress.total} photos"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(6.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth()
        )
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
 * photos would otherwise pull tens of megabytes to render a screen of small squares.
 */
@Composable
private fun PhotoTile(
    photo: Photo,
    isSelected: Boolean,
    isSelecting: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.thumbnailUrl)
                .crossfade(true)
                .build(),
            contentDescription = photo.caption ?: "Photo by ${photo.uploaderName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // Shrinking the selected tile shows the check without hiding the photo.
                .padding(if (isSelected) 10.dp else 0.dp)
                .clip(MaterialTheme.shapes.extraSmall)
        )

        if (isSelecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.35f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (isFavorite && !isSelecting) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = "Starred",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(16.dp)
            )
        }

        if (photo.totalReactions > 0 && !isSelecting) {
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
                Text("Failed", style = MaterialTheme.typography.labelMedium, color = Color.White)
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

private fun emptyFilterTitle(filter: PhotoFilter): String = when (filter) {
    PhotoFilter.Favorites -> "Nothing starred yet"
    is PhotoFilter.ByUploader -> "No photos from ${filter.name.substringBefore(" ")}"
    PhotoFilter.All -> "No photos yet"
}
