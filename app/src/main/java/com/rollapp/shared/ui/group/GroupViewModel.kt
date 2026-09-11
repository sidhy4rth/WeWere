package com.rollapp.shared.ui.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.Member
import com.rollapp.shared.domain.model.PendingUpload
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.PhotoFilter
import com.rollapp.shared.domain.model.TimelineItem
import com.rollapp.shared.domain.model.UploadState
import com.rollapp.shared.domain.repository.AuthRepository
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.domain.repository.PhotoRepository
import com.rollapp.shared.domain.repository.UploadQueueRepository
import com.rollapp.shared.domain.usecase.BuildTimelineUseCase
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupUiState(
    val group: Group? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val members: List<Member> = emptyList(),
    val pendingUploads: List<PendingUpload> = emptyList(),
    val myUid: String? = null,
    val isAdmin: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val filter: PhotoFilter = PhotoFilter.All,
    /** Empty means normal browsing; non-empty puts the grid in selection mode. */
    val selectedIds: Set<String> = emptySet(),
    val bulkProgress: BulkProgress? = null,
    /** Set when the user stops being a member while the screen is open. */
    val accessRevoked: Boolean = false,
    val transientError: AppError? = null
) {
    val isEmpty: Boolean get() = !isLoading && photos.isEmpty() && pendingUploads.isEmpty()
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()
    val selectedPhotos: List<Photo> get() = photos.filter { it.id in selectedIds }

    /** How many of the selection this user is actually allowed to remove. */
    fun deletableCount(): Int =
        selectedPhotos.count { isAdmin || it.uploadedBy == myUid }
    val uploadingCount: Int get() = pendingUploads.count { it.state != UploadState.FAILED }
    val failedCount: Int get() = pendingUploads.count { it.state == UploadState.FAILED }
}

@HiltViewModel
class GroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val photoRepository: PhotoRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val authRepository: AuthRepository,
    private val buildTimeline: BuildTimelineUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])

    private val transientError = MutableStateFlow<AppError?>(null)
    private val loadingMore = MutableStateFlow(false)
    private val filter = MutableStateFlow<PhotoFilter>(PhotoFilter.All)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val bulkProgress = MutableStateFlow<BulkProgress?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val photoPage = filter.flatMapLatest { active ->
        photoRepository.resetPagination(groupId)
        photoRepository.observePhotos(groupId, active)
    }

    private val content = combine(
        groupRepository.observeGroup(groupId),
        photoPage,
        groupRepository.observeMembers(groupId),
        uploadQueueRepository.observePendingForGroup(groupId),
        groupRepository.observeMembership(groupId)
    ) { group, page, members, pending, membership ->
        val myUid = authRepository.currentUid()
        val ordered = buildTimeline.orderedPhotos(page.photos)

        GroupUiState(
            group = group,
            timeline = buildTimeline(page.photos),
            photos = ordered,
            members = members,
            pendingUploads = pending,
            myUid = myUid,
            isAdmin = membership?.isAdmin == true,
            hasMore = page.hasMore,
            isLoading = false,
            // A null membership while the group still exists means an admin removed
            // this user — distinct from the group itself being deleted.
            accessRevoked = membership == null && group != null && myUid != null
        )
    }

    val state: StateFlow<GroupUiState> = combine(
        content, transientError, filter, selectedIds, bulkProgress
    ) { base, error, active, selection, progress ->
        base.copy(
            transientError = error,
            filter = active,
            // Drop ids that have scrolled out of the window or been deleted, so the
            // count in the toolbar always matches what is actually selected.
            selectedIds = selection intersect base.photos.map { it.id }.toSet(),
            bulkProgress = progress
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GroupUiState()
    )

    fun loadOlder() {
        if (loadingMore.value) return
        viewModelScope.launch {
            loadingMore.value = true
            photoRepository.loadOlder(groupId)
            loadingMore.value = false
        }
    }

    fun uploadFromGallery(uris: List<Uri>, caption: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                photoRepository.enqueueUpload(
                    groupId = groupId,
                    localUri = uri,
                    caption = caption,
                    // Gallery picks carry no reliable capture time at this point; the
                    // worker reads EXIF off the staged copy and corrects it.
                    capturedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun retryFailed() {
        viewModelScope.launch { uploadQueueRepository.retryAllFailed() }
    }

    fun cancelUpload(uploadId: String) {
        viewModelScope.launch { uploadQueueRepository.cancel(uploadId) }
    }

    // ------------------------------------------------------------------ filters

    fun setFilter(next: PhotoFilter) {
        if (filter.value == next) return
        clearSelection()
        filter.value = next
    }

    fun clearFilter() = setFilter(PhotoFilter.All)

    // ---------------------------------------------------------------- selection

    fun toggleSelection(photoId: String) {
        selectedIds.value = selectedIds.value.let {
            if (photoId in it) it - photoId else it + photoId
        }
    }

    fun selectAll() {
        selectedIds.value = state.value.photos.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    // ------------------------------------------------------------------ actions

    fun toggleFavorite(photo: Photo) {
        viewModelScope.launch {
            photoRepository.setFavorite(
                groupId = groupId,
                photoId = photo.id,
                favorite = !photo.isFavoritedBy(state.value.myUid)
            )
        }
    }

    /** Saves the current selection to the device, reporting progress as it runs. */
    fun saveSelectionToDevice(onDone: (Int) -> Unit) {
        val chosen = state.value.selectedPhotos
        if (chosen.isEmpty()) return

        viewModelScope.launch {
            bulkProgress.value = BulkProgress(BulkAction.SAVING, 0, chosen.size)

            val outcome = photoRepository.downloadAllToGallery(chosen) { done, total ->
                bulkProgress.value = BulkProgress(BulkAction.SAVING, done, total)
            }

            bulkProgress.value = null
            when (outcome) {
                is Outcome.Success -> {
                    clearSelection()
                    onDone(outcome.data)
                }
                is Outcome.Failure -> transientError.value = outcome.error
            }
        }
    }

    /** Removes only the photos this user may remove; the UI states the count first. */
    fun deleteSelection(onDone: (Int) -> Unit) {
        val current = state.value
        val removable = current.selectedPhotos
            .filter { current.isAdmin || it.uploadedBy == current.myUid }
            .map { it.id }
        if (removable.isEmpty()) return

        viewModelScope.launch {
            bulkProgress.value = BulkProgress(BulkAction.DELETING, 0, removable.size)

            when (val outcome = photoRepository.deletePhotos(groupId, removable)) {
                is Outcome.Success -> {
                    clearSelection()
                    onDone(outcome.data)
                }
                is Outcome.Failure -> transientError.value = outcome.error
            }
            bulkProgress.value = null
        }
    }

    fun dismissError() {
        transientError.value = null
    }
}

enum class BulkAction { SAVING, DELETING }

data class BulkProgress(val action: BulkAction, val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}
