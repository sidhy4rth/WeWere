package com.rollapp.shared.ui.carousel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.MemberRole
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.Reaction
import com.rollapp.shared.domain.repository.AuthRepository
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.domain.repository.PhotoRepository
import com.rollapp.shared.domain.usecase.BuildTimelineUseCase
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CarouselUiState(
    val photos: List<Photo> = emptyList(),
    val myUid: String? = null,
    val isAdmin: Boolean = false,
    val isLoading: Boolean = true
)

sealed interface CarouselEvent {
    data class Saved(val message: String) : CarouselEvent
    data class ShareReady(val uri: android.net.Uri, val caption: String?) : CarouselEvent
    data class Failed(val error: AppError) : CarouselEvent
    data object Deleted : CarouselEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CarouselViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
    private val buildTimeline: BuildTimelineUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])
    val initialPhotoId: String? = savedStateHandle[NavArgs.PHOTO_ID]

    private val _events = MutableStateFlow<CarouselEvent?>(null)
    val events: StateFlow<CarouselEvent?> = _events.asStateFlow()

    private val currentPhotoId = MutableStateFlow(initialPhotoId)

    val state: StateFlow<CarouselUiState> = combine(
        photoRepository.observePhotos(groupId),
        groupRepository.observeMembership(groupId)
    ) { page, membership ->
        CarouselUiState(
            // Identical ordering to the grid, so tapping tile N lands on page N.
            photos = buildTimeline.orderedPhotos(page.photos),
            myUid = authRepository.currentUid(),
            isAdmin = membership?.role == MemberRole.ADMIN,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CarouselUiState()
    )

    /**
     * The viewer's own reaction for whichever photo is on screen. It lives in its own
     * document, so this watches only the current page rather than fetching a reaction
     * for every photo in the feed.
     */
    val currentReaction: StateFlow<Reaction?> = currentPhotoId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else photoRepository.observePhoto(groupId, id)
                .map { Reaction.fromKey(it?.myReaction) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onPageChanged(photoId: String) {
        currentPhotoId.value = photoId
    }

    fun loadOlder() {
        viewModelScope.launch { photoRepository.loadOlder(groupId) }
    }

    /** Tapping the reaction already set clears it, which is what users expect. */
    fun toggleReaction(photoId: String, reaction: Reaction) {
        viewModelScope.launch {
            val next = if (currentReaction.value == reaction) null else reaction
            photoRepository.setReaction(groupId, photoId, next)
        }
    }

    fun setCaption(photoId: String, caption: String?) {
        viewModelScope.launch { photoRepository.setCaption(groupId, photoId, caption) }
    }

    fun deletePhoto(photoId: String) {
        viewModelScope.launch {
            when (val outcome = photoRepository.deletePhoto(groupId, photoId)) {
                is Outcome.Success -> _events.value = CarouselEvent.Deleted
                is Outcome.Failure -> _events.value = CarouselEvent.Failed(outcome.error)
            }
        }
    }

    fun download(photo: Photo) {
        viewModelScope.launch {
            _events.value = when (val outcome = photoRepository.downloadToGallery(photo)) {
                is Outcome.Success -> CarouselEvent.Saved("Saved to your gallery")
                is Outcome.Failure -> CarouselEvent.Failed(outcome.error)
            }
        }
    }

    fun share(photo: Photo) {
        viewModelScope.launch {
            _events.value = when (val outcome = photoRepository.prepareForSharing(photo)) {
                is Outcome.Success -> CarouselEvent.ShareReady(outcome.data, photo.caption)
                is Outcome.Failure -> CarouselEvent.Failed(outcome.error)
            }
        }
    }

    fun report(photoId: String, reason: String) {
        viewModelScope.launch {
            photoRepository.reportPhoto(groupId, photoId, reason)
            _events.value = CarouselEvent.Saved("Reported — thanks for flagging it")
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    fun canDelete(photo: Photo): Boolean =
        photo.uploadedBy == state.value.myUid || state.value.isAdmin
}
