package com.rollapp.shared.ui.group

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rollapp.shared.core.Limits
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.PhotoFilter
import com.rollapp.shared.domain.model.PhotoFilterCodec
import com.rollapp.shared.domain.model.TimelineItem
import com.rollapp.shared.domain.model.UploadState
import com.rollapp.shared.ui.components.DevelopingImage
import com.rollapp.shared.ui.components.GoldButton
import com.rollapp.shared.ui.components.GoldChip
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.components.RiseIn
import com.rollapp.shared.ui.components.Shutter
import com.rollapp.shared.ui.components.UserAvatar
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.IvoryMuted
import com.rollapp.shared.ui.theme.Muted
import com.rollapp.shared.ui.theme.OnGold
import com.rollapp.shared.ui.theme.Raised
import com.rollapp.shared.ui.theme.Surface
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
    BackHandler(enabled = state.isSelecting) {
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

    // Each day's hero is its most-starred photo (ties go to the newest), so the
    // section opens on the shot the group itself voted for.
    val heroIds = remember(state.timeline) { pickHeroes(state.timeline) }

    Scaffold(
        containerColor = Ink,
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
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 170.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (!state.isSelecting) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        GroupHeader(
                            state = state,
                            onBack = onBack,
                            onOpenMembers = { onOpenMembers(viewModel.groupId) },
                            onOpenSettings = { onOpenSettings(viewModel.groupId) },
                            onOpenSlideshow = {
                                onOpenSlideshow(viewModel.groupId, PhotoFilterCodec.encode(state.filter))
                            },
                            onFilter = viewModel::setFilter
                        )
                    }
                }

                state.bulkProgress?.let { progress ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "bulk") { BulkProgressBar(progress) }
                }

                if (state.failedCount > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "failed") {
                        InlineError(
                            message = "${state.failedCount} ${if (state.failedCount == 1) "photo" else "photos"} didn't upload",
                            actionLabel = "Retry",
                            onAction = viewModel::retryFailed
                        )
                    }
                }

                when {
                    state.isEmpty && state.filter.isActive -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        EmptyRoll(
                            title = emptyFilterTitle(state.filter),
                            body = "Nothing here yet. Clear the filter to see the whole roll.",
                            actionLabel = "Show all photos",
                            onAction = viewModel::clearFilter
                        )
                    }

                    state.isEmpty -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        EmptyRoll(
                            title = "Nothing developed yet",
                            body = "Take the first one — everyone on the roll sees it straight away."
                        )
                    }

                    else -> {
                        if (state.pendingUploads.isNotEmpty() && !state.filter.isActive) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "uploading-header") {
                                SectionHeader("Uploading")
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

                                is TimelineItem.Item -> {
                                    val photo = entry.photo
                                    val isHero = photo.id in heroIds && !state.isSelecting
                                    item(
                                        key = photo.id,
                                        span = { if (isHero) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                                    ) {
                                        val onClick = {
                                            if (state.isSelecting) {
                                                viewModel.toggleSelection(photo.id)
                                            } else {
                                                onOpenPhoto(
                                                    viewModel.groupId,
                                                    photo.id,
                                                    PhotoFilterCodec.encode(state.filter)
                                                )
                                            }
                                        }
                                        val onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleSelection(photo.id)
                                        }
                                        if (isHero) {
                                            HeroTile(photo = photo, onClick = onClick, onLongClick = onLongClick)
                                        } else {
                                            PhotoTile(
                                                photo = photo,
                                                isSelected = photo.id in state.selectedIds,
                                                isSelecting = state.isSelecting,
                                                isFavorite = photo.isFavoritedBy(state.myUid),
                                                onClick = onClick,
                                                onLongClick = onLongClick
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (state.hasMore) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = Gold,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The shutter bar. The camera has no place in selection mode; the action
            // bar owns the screen then.
            AnimatedVisibility(
                visible = !state.isSelecting,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Ink))
                        .padding(top = 56.dp, bottom = 16.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RingButton(
                        icon = Icons.Rounded.PhotoLibrary,
                        contentDescription = "Upload from gallery",
                        onClick = {
                            galleryPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    Shutter(onClick = { onOpenCamera(viewModel.groupId) })
                    RingButton(
                        icon = Icons.Rounded.People,
                        contentDescription = "Members",
                        onClick = { onOpenMembers(viewModel.groupId) }
                    )
                }
            }
        }
    }
}

/** Title block: nav row, name, readout, the people, then the filters. */
@Composable
private fun GroupHeader(
    state: GroupUiState,
    onBack: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSlideshow: () -> Unit,
    onFilter: (PhotoFilter) -> Unit
) {
    Column(modifier = Modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Back", tint = Ivory, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            if (state.photos.isNotEmpty()) {
                IconButton(onClick = onOpenSlideshow) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play slideshow", tint = Ivory)
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Group settings", tint = Ivory)
            }
        }

        RiseIn {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = state.group?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Ivory,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                state.group?.let { group ->
                    Readout(
                        text = "${group.photoCount} ${if (group.photoCount == 1) "exposure" else "exposures"} · " +
                            "${group.memberCount} ${if (group.memberCount == 1) "friend" else "friends"}",
                        color = Gold
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // People double as the uploader filter: tap a face to see only their shots.
        RiseIn(delayMillis = 80) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.members.forEach { member ->
                    val selected = (state.filter as? PhotoFilter.ByUploader)?.uid == member.uid
                    UserAvatar(
                        name = member.name,
                        photoUrl = member.photoUrl,
                        seed = member.uid,
                        size = 34.dp,
                        borderColor = if (selected) Gold else null,
                        modifier = Modifier.clickable {
                            onFilter(
                                if (selected) PhotoFilter.All
                                else PhotoFilter.ByUploader(member.uid, member.name)
                            )
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(1.dp, Gold.copy(alpha = 0.6f), CircleShape)
                        .clickable(onClick = onOpenMembers),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.People, contentDescription = "Members", tint = Gold, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        RiseIn(delayMillis = 140) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldChip(
                    text = "All",
                    selected = state.filter is PhotoFilter.All,
                    onClick = { onFilter(PhotoFilter.All) }
                )
                GoldChip(
                    text = "Starred",
                    selected = state.filter is PhotoFilter.Favorites,
                    onClick = { onFilter(PhotoFilter.Favorites) },
                    icon = Icons.Rounded.Star
                )
                (state.filter as? PhotoFilter.ByUploader)?.let { by ->
                    GoldChip(
                        text = if (by.uid == state.myUid) "You" else by.name.substringBefore(" "),
                        selected = true,
                        onClick = { onFilter(PhotoFilter.All) }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}


@Composable
private fun RingButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.6f))
            .border(1.dp, Ivory.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ivory, modifier = Modifier.size(20.dp))
    }
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
    Column(modifier = Modifier.background(Ink)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel selection", tint = Ivory)
            }
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.headlineSmall,
                color = Ivory,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Rounded.SelectAll, contentDescription = "Select all", tint = Gold)
            }
            IconButton(onClick = onSave) {
                Icon(Icons.Rounded.Download, contentDescription = "Save to device", tint = Gold)
            }
            // Hidden rather than disabled when none of the selection is yours: a
            // greyed-out bin invites a tap that can never work.
            if (deletableCount > 0) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Gold)
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun BulkProgressBar(progress: BulkProgress) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Readout(
            text = when (progress.action) {
                BulkAction.SAVING -> "Saving ${progress.done} of ${progress.total} to your gallery"
                BulkAction.DELETING -> "Deleting ${progress.total} photos"
            },
            color = IvoryMuted
        )
        Spacer(Modifier.size(8.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            color = Gold,
            trackColor = Raised,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.headlineSmall,
        color = Ivory,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun EmptyRoll(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RiseIn(delayMillis = 200) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Ivory,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        RiseIn(delayMillis = 260) { Hairline(Modifier.width(120.dp)) }
        RiseIn(delayMillis = 320) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            RiseIn(delayMillis = 400) { GoldButton(text = actionLabel, onClick = onAction) }
        }
    }
}

/**
 * The day's opening shot, full width. A gold shimmer crosses it once on arrival.
 */
@Composable
private fun HeroTile(photo: Photo, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(342f / 200f)
            .clip(MaterialTheme.shapes.medium)
            .background(Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        DevelopingImage(
            model = photo.imageUrl,
            contentDescription = photo.caption ?: "Photo by ${photo.uploaderName}",
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Ink.copy(alpha = 0.85f)))
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Rounded.Star, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
            Readout(
                text = (if (photo.favoritedBy.isNotEmpty()) "Most starred · " else "Latest · ") +
                    photo.uploaderName.substringBefore(" "),
                color = Ivory
            )
        }
    }
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
            .clip(MaterialTheme.shapes.small)
            .background(Surface)
            .then(if (isSelected) Modifier.border(2.dp, Gold, MaterialTheme.shapes.small) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        DevelopingImage(
            model = photo.thumbnailUrl,
            contentDescription = photo.caption ?: "Photo by ${photo.uploaderName}",
            modifier = Modifier
                .fillMaxSize()
                // Shrinking the selected tile shows the ring without hiding the photo.
                .padding(if (isSelected) 6.dp else 0.dp)
                .clip(MaterialTheme.shapes.extraSmall)
        )

        if (isSelecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Gold else Ink.copy(alpha = 0.55f))
                    .then(if (isSelected) Modifier else Modifier.border(1.dp, Ivory.copy(alpha = 0.5f), CircleShape)),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = OnGold, modifier = Modifier.size(14.dp))
                }
            }
        }

        if (isFavorite && !isSelecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Star, contentDescription = "Starred", tint = Gold, modifier = Modifier.size(12.dp))
            }
        }

        if (photo.totalReactions > 0 && !isSelecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = 0.7f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Readout(text = "${photo.totalReactions}", color = Ivory)
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
            .clip(MaterialTheme.shapes.small)
            .background(Surface)
    ) {
        AsyncImage(
            model = localUri,
            contentDescription = "Uploading",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            if (isFailed) {
                Readout(text = "Failed", color = Ivory)
            } else {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    strokeWidth = 3.dp,
                    color = Gold,
                    trackColor = Ivory.copy(alpha = 0.2f),
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel upload", tint = Ivory, modifier = Modifier.size(16.dp))
        }
    }
}

/** One hero per section: the most-starred photo, newest first on a tie. */
private fun pickHeroes(timeline: List<TimelineItem>): Set<String> {
    val heroes = mutableSetOf<String>()
    var best: Photo? = null
    fun close() { best?.let { heroes += it.id }; best = null }
    for (entry in timeline) {
        when (entry) {
            is TimelineItem.Header -> close()
            is TimelineItem.Item -> {
                val current = best
                if (current == null || entry.photo.favoritedBy.size > current.favoritedBy.size) {
                    best = entry.photo
                }
            }
        }
    }
    close()
    return heroes
}

private fun emptyFilterTitle(filter: PhotoFilter): String = when (filter) {
    PhotoFilter.Favorites -> "Nothing starred yet"
    is PhotoFilter.ByUploader -> "No photos from ${filter.name.substringBefore(" ")}"
    PhotoFilter.All -> "No photos yet"
}
