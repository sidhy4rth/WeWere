package com.rollapp.shared.ui.camera

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Limits
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.repository.PhotoRepository
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val caption: String = "",
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    val shared: Boolean = false
) {
    val captionRemaining: Int get() = Limits.MAX_CAPTION_LENGTH - caption.length
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])
    val photoUri: Uri = Uri.parse(checkNotNull(savedStateHandle[NavArgs.URI]))
    private val capturedAt: Long = savedStateHandle.get<String>(NavArgs.CAPTURED_AT)
        ?.toLongOrNull()
        ?: System.currentTimeMillis()

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    fun onCaptionChange(value: String) {
        if (value.length > Limits.MAX_CAPTION_LENGTH) return
        _state.update { it.copy(caption = value) }
    }

    /**
     * Queues rather than uploads. The user gets back to the group immediately and the
     * worker delivers the photo whenever the network allows — which is the difference
     * between the app working on a train and not.
     */
    fun shareToGroup() {
        if (_state.value.isSubmitting) return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }

            val outcome = photoRepository.enqueueUpload(
                groupId = groupId,
                localUri = photoUri,
                caption = _state.value.caption.takeIf { it.isNotBlank() },
                capturedAt = capturedAt
            )

            _state.update {
                when (outcome) {
                    is Outcome.Success -> it.copy(isSubmitting = false, shared = true)
                    is Outcome.Failure -> it.copy(isSubmitting = false, error = outcome.error)
                }
            }
        }
    }
}
