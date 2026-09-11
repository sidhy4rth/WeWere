package com.rollapp.shared.ui.carousel

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.components.Settle
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.IvoryMuted
import com.rollapp.shared.ui.theme.Muted
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rollapp.shared.core.Limits
import com.rollapp.shared.core.TimeFormat
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.Reaction
import com.rollapp.shared.ui.components.Sharing
import com.rollapp.shared.ui.components.UserAvatar
import com.rollapp.shared.ui.theme.PhotoScrimBottom
import com.rollapp.shared.ui.theme.PhotoScrimTop
import kotlin.math.abs
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CarouselScreen(
    onClose: () -> Unit,
    viewModel: CarouselViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val myReaction by viewModel.currentReaction.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var controlsVisible by remember { mutableStateOf(true) }
    var isZoomed by remember { mutableStateOf(false) }
    var dismissOffset by remember { mutableStateOf(0f) }
    var editingCaptionFor by remember { mutableStateOf<Photo?>(null) }

    // Scoped storage arrived in Android 10; below that, writing into the shared
    // Pictures collection needs an explicit grant. Above it, asking would be both
    // pointless and refused.
    val needsLegacyWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val writePermission = if (needsLegacyWrite) {
        rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        null
    }
    var pendingDownload by remember { mutableStateOf<Photo?>(null) }
    var slideshowRunning by remember { mutableStateOf(viewModel.startsInSlideshow) }

    fun requestDownload(photo: Photo) {
        if (writePermission == null || writePermission.status.isGranted) {
            viewModel.download(photo)
        } else {
            pendingDownload = photo
            writePermission.launchPermissionRequest()
        }
    }

    // Resume the save the moment the grant lands, so the user does not have to tap
    // Save a second time.
    LaunchedEffect(writePermission?.status?.isGranted) {
        val photo = pendingDownload ?: return@LaunchedEffect
        if (writePermission?.status?.isGranted == true) {
            viewModel.download(photo)
            pendingDownload = null
        }
    }

    val startIndex = remember(state.photos, viewModel.initialPhotoId) {
        state.photos.indexOfFirst { it.id == viewModel.initialPhotoId }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { state.photos.size }
    )

    // Re-seed once the feed arrives, otherwise the pager opens on page 0 while the
    // photo list is still empty and the tapped photo is never shown.
    LaunchedEffect(state.photos.isNotEmpty()) {
        if (state.photos.isNotEmpty() && startIndex > 0 && pagerState.currentPage == 0) {
            pagerState.scrollToPage(startIndex)
        }
    }

    /**
     * Autoplay. Keeping the screen awake while it runs is the point — a slideshow
     * that blanks after thirty seconds is worse than no slideshow, and this is the
     * one moment the phone is deliberately propped up and not being touched.
     */
    val view = LocalView.current
    DisposableEffect(slideshowRunning) {
        view.keepScreenOn = slideshowRunning
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(slideshowRunning, state.photos.size) {
        if (!slideshowRunning || state.photos.isEmpty()) return@LaunchedEffect
        controlsVisible = false
        while (true) {
            delay(SLIDE_INTERVAL_MS)
            val next = pagerState.currentPage + 1
            if (next >= state.photos.size) {
                // Stop at the end rather than looping; a loop hides that it finished.
                if (!state.hasMoreToLoad) {
                    slideshowRunning = false
                    controlsVisible = true
                    break
                }
                viewModel.loadOlder()
            } else {
                pagerState.animateScrollToPage(next)
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            state.photos.getOrNull(page)?.let { viewModel.onPageChanged(it.id) }
            // Reaching the end of the loaded window pulls in the next page.
            if (page >= state.photos.size - 3) viewModel.loadOlder()
        }
    }

    LaunchedEffect(event) {
        when (val current = event) {
            is CarouselEvent.Saved -> {
                snackbarHostState.showSnackbar(current.message)
                viewModel.consumeEvent()
            }
            is CarouselEvent.ShareReady -> {
                Sharing.sharePhoto(context, current.uri, current.caption)
                viewModel.consumeEvent()
            }
            is CarouselEvent.Failed -> {
                snackbarHostState.showSnackbar(current.error.message ?: "That didn't work")
                viewModel.consumeEvent()
            }
            CarouselEvent.Deleted -> {
                viewModel.consumeEvent()
                if (state.photos.size <= 1) onClose()
            }
            null -> Unit
        }
    }

    editingCaptionFor?.let { photo ->
        CaptionDialog(
            initial = photo.caption.orEmpty(),
            onDismiss = { editingCaptionFor = null },
            onSave = { text ->
                viewModel.setCaption(photo.id, text.takeIf { it.isNotBlank() })
                editingCaptionFor = null
            }
        )
    }

    val dismissProgress = (abs(dismissOffset) / DISMISS_THRESHOLD_PX).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Fading the backdrop as the photo is dragged away is what makes the
            // gesture feel like dismissal rather than a scroll that went wrong.
            .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.6f))
    ) {
        if (state.isLoading && state.photos.isEmpty()) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        HorizontalPager(
            state = pagerState,
            // One page either side stays decoded, so a swipe shows the next photo
            // immediately instead of a placeholder.
            beyondViewportPageCount = 1,
            userScrollEnabled = !isZoomed,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissOffset
                    val shrink = 1f - dismissProgress * 0.12f
                    scaleX = shrink
                    scaleY = shrink
                }
                .pointerInput(isZoomed) {
                    if (isZoomed) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (abs(dismissOffset) > DISMISS_THRESHOLD_PX) onClose()
                            else dismissOffset = 0f
                        },
                        onDragCancel = { dismissOffset = 0f },
                        onVerticalDrag = { _, delta -> dismissOffset += delta }
                    )
                }
        ) { page ->
            val photo = state.photos.getOrNull(page) ?: return@HorizontalPager

            ZoomableImage(
                onTap = {
                    if (slideshowRunning) {
                        slideshowRunning = false
                        controlsVisible = true
                    } else {
                        controlsVisible = !controlsVisible
                    }
                },
                onZoomChanged = { isZoomed = it }
            ) { imageModifier ->
                val painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(photo.imageUrl)
                        // The cached grid thumbnail fills the frame instantly while the
                        // full-resolution image decodes over it.
                        .placeholderMemoryCacheKey(photo.thumbnailUrl)
                        .crossfade(400)
                        .build()
                )

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = photo.caption ?: "Photo by ${photo.uploaderName}",
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier
                    )

                    if (painter.state is AsyncImagePainter.State.Loading) {
                        AsyncImage(
                            model = photo.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        val currentPhoto = state.photos.getOrNull(pagerState.currentPage)

        AnimatedVisibility(
            visible = controlsVisible && currentPhoto != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            currentPhoto?.let { photo ->
                TopControls(
                    photo = photo,
                    canDelete = viewModel.canDelete(photo),
                    canEditCaption = photo.uploadedBy == state.myUid,
                    isSlideshowRunning = slideshowRunning,
                    onToggleSlideshow = {
                        slideshowRunning = !slideshowRunning
                        if (slideshowRunning) controlsVisible = false
                    },
                    onClose = onClose,
                    onDelete = { viewModel.deletePhoto(photo.id) },
                    onEditCaption = { editingCaptionFor = photo },
                    onReport = { viewModel.report(photo.id, "inappropriate") }
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && currentPhoto != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentPhoto?.let { photo ->
                BottomControls(
                    photo = photo,
                    position = pagerState.currentPage,
                    total = state.photos.size,
                    myReaction = myReaction,
                    isFavorite = photo.isFavoritedBy(state.myUid),
                    canDelete = viewModel.canDelete(photo),
                    onReact = { viewModel.toggleReaction(photo.id, it) },
                    onToggleFavorite = { viewModel.toggleFavorite(photo) },
                    onShare = { viewModel.share(photo) },
                    onDownload = { requestDownload(photo) },
                    onDelete = { viewModel.deletePhoto(photo.id) }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 100.dp)
        )
    }
}

@Composable
private fun TopControls(
    photo: Photo,
    canDelete: Boolean,
    canEditCaption: Boolean,
    isSlideshowRunning: Boolean,
    onToggleSlideshow: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onEditCaption: () -> Unit,
    onReport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(PhotoScrimTop, Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Close", tint = Ivory, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UserAvatar(
                name = photo.uploaderName,
                photoUrl = photo.uploaderPhotoUrl,
                seed = photo.uploadedBy,
                size = 32.dp,
                borderColor = Gold
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = photo.uploaderName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ivory,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Readout(text = TimeFormat.relative(photo.capturedAt ?: photo.createdAt), color = IvoryMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Ivory)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (isSlideshowRunning) "Pause slideshow" else "Play slideshow") },
                    onClick = { menuOpen = false; onToggleSlideshow() }
                )
                if (canEditCaption) {
                    DropdownMenuItem(
                        text = { Text(if (photo.caption.isNullOrBlank()) "Add a caption" else "Edit caption") },
                        onClick = { menuOpen = false; onEditCaption() }
                    )
                }
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text("Delete photo") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Report photo") },
                    onClick = { menuOpen = false; onReport() }
                )
            }
        }
    }
}

/**
 * Caption, who starred it, reactions, then the four verbs. The brief was explicit
 * that this should not drift into a social feed, so there is no comment thread, no
 * view count and no share-back.
 */
@Composable
private fun BottomControls(
    photo: Photo,
    position: Int,
    total: Int,
    myReaction: Reaction?,
    isFavorite: Boolean,
    canDelete: Boolean,
    onReact: (Reaction) -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, PhotoScrimBottom)))
            .padding(top = 40.dp)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!photo.caption.isNullOrBlank()) {
            Text(
                text = photo.caption,
                style = MaterialTheme.typography.headlineSmall,
                color = Ivory,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (photo.favoritedBy.isNotEmpty()) {
                Readout(
                    text = "Starred by ${photo.favoritedBy.size}",
                    color = Gold
                )
            }
            Spacer(Modifier.weight(1f))
            Readout(text = "${position + 1} / $total", color = Muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Reaction.entries.forEach { reaction ->
                val count = photo.reactionCounts[reaction.key] ?: 0
                val selected = myReaction == reaction

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) Gold.copy(alpha = 0.22f) else Ink.copy(alpha = 0.55f))
                        .then(
                            if (selected) Modifier.border(1.dp, Gold, CircleShape)
                            else Modifier.border(1.dp, Ivory.copy(alpha = 0.15f), CircleShape)
                        )
                        .clickable { onReact(reaction) }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = reaction.emoji, style = MaterialTheme.typography.bodyMedium)
                    if (count > 0) {
                        Spacer(Modifier.width(5.dp))
                        Readout(text = "$count", color = if (selected) Gold else Ivory)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            StarAction(isFavorite = isFavorite, onClick = onToggleFavorite)
            ViewerAction(icon = Icons.Rounded.Share, label = "Share", onClick = onShare)
            ViewerAction(icon = Icons.Rounded.Download, label = "Save", onClick = onDownload)
            if (canDelete) {
                ViewerAction(icon = Icons.Rounded.Delete, label = "Delete", onClick = onDelete)
            }
        }
    }
}

@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Ivory,
    ring: Color = Ivory.copy(alpha = 0.2f),
    fill: Color = Color.Transparent
) {
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, ring, CircleShape)
                .clickable(onClick = onClick, onClickLabel = label),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Readout(text = label, color = if (tint == Gold) Gold else IvoryMuted)
    }
}

/** The star pops and throws a ring when it lights up; unstarring is quiet. */
@Composable
private fun StarAction(isFavorite: Boolean, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    var wasFavorite by remember { mutableStateOf(isFavorite) }
    LaunchedEffect(isFavorite) {
        if (isFavorite && !wasFavorite) {
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(700, easing = Settle)) }
            scale.snapTo(0.6f)
            scale.animateTo(1.25f, tween(220, easing = Settle))
            scale.animateTo(1f, tween(260, easing = Settle))
        }
        wasFavorite = isFavorite
    }
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .drawBehind {
                    val b = burst.value
                    if (b > 0f && b < 1f) {
                        drawCircle(
                            color = Gold.copy(alpha = 0.9f * (1f - b)),
                            radius = (size.minDimension / 2f) * (0.4f + 1.8f * b),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
                .clip(CircleShape)
                .background(if (isFavorite) Gold.copy(alpha = 0.14f) else Color.Transparent)
                .border(1.dp, if (isFavorite) Gold else Ivory.copy(alpha = 0.2f), CircleShape)
                .clickable(onClick = onClick, onClickLabel = if (isFavorite) "Remove star" else "Star this photo"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = if (isFavorite) Gold else Ivory,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            )
        }
        Readout(text = if (isFavorite) "Starred" else "Star", color = if (isFavorite) Gold else IvoryMuted)
    }
}

@Composable
private fun CaptionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caption") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= Limits.MAX_CAPTION_LENGTH) draft = it },
                placeholder = { Text("Bro thought he could drive 😭") },
                maxLines = 3,
                supportingText = { Text("${Limits.MAX_CAPTION_LENGTH - draft.length} left") }
            )
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** How far the photo must be dragged before letting go closes the viewer. */
private const val DISMISS_THRESHOLD_PX = 320f

/** Long enough to actually look at a photo, short enough to hold a room's attention. */
private const val SLIDE_INTERVAL_MS = 3_500L
