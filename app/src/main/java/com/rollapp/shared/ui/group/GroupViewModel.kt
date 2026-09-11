package com.rollapp.shared.ui.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.Member
import com.rollapp.shared.domain.model.PendingUpload
import com.rollapp.shared.domain.model.Photo
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
    /** Set when the user stops being a member while the screen is open. */
    val accessRevoked: Boolean = false,
    val transientError: AppError? = null
) {
    val isEmpty: Boolean get() = !isLoading && photos.isEmpty() && pendingUploads.isEmpty()
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

    private val content = combine(
        groupRepository.observeGroup(groupId),
        photoRepository.observePhotos(groupId),
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

    val state: StateFlow<GroupUiState> = combine(content, transientError) { base, error ->
        base.copy(transientError = error)
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

    fun dismissError() {
        transientError.value = null
    }
}
