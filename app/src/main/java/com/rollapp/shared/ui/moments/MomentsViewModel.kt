package com.rollapp.shared.ui.moments

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.data.moments.MomentReminders
import com.rollapp.shared.data.moments.MomentRepository
import com.rollapp.shared.data.moments.MontageMaker
import com.rollapp.shared.domain.model.Moment
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MomentsUiState(
    val moments: List<Moment> = emptyList(),
    /** 0..1 while a clip is uploading. */
    val uploadProgress: Float? = null,
    /** What the montage maker is doing, while it runs. */
    val montageStatus: String? = null,
    val reminderMinute: Int? = null,
    val message: String? = null
)

@HiltViewModel
class MomentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MomentRepository,
    private val montageMaker: MontageMaker,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])
    private val local = MutableStateFlow(MomentsUiState(reminderMinute = MomentReminders.get(context, groupId)))

    val state: StateFlow<MomentsUiState> = combine(local, repository.observeThisWeek(groupId)) { s, m ->
        s.copy(moments = m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun upload(video: Uri) {
        if (local.value.uploadProgress != null) return
        viewModelScope.launch {
            local.update { it.copy(uploadProgress = 0f, message = null) }
            val outcome = repository.upload(groupId, video) { p -> local.update { it.copy(uploadProgress = p) } }
            local.update {
                it.copy(
                    uploadProgress = null,
                    message = when (outcome) {
                        is Outcome.Success -> "Moment added"
                        is Outcome.Failure -> outcome.error.message ?: "Upload failed"
                    }
                )
            }
        }
    }

    fun setReminder(rollName: String, minuteOfDay: Int) {
        MomentReminders.set(context, groupId, rollName, minuteOfDay)
        local.update { it.copy(reminderMinute = minuteOfDay, message = "Reminder set") }
    }

    fun clearReminder() {
        MomentReminders.clear(context, groupId)
        local.update { it.copy(reminderMinute = null, message = "Reminder off") }
    }

    fun makeMontage(rollName: String) {
        val clips = state.value.moments
        if (local.value.montageStatus != null || clips.isEmpty()) return
        viewModelScope.launch {
            try {
                montageMaker.make(rollName, clips) { status -> local.update { it.copy(montageStatus = status) } }
                local.update { it.copy(montageStatus = null, message = "Montage saved to your gallery") }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                local.update { it.copy(montageStatus = null, message = "Montage failed: ${t.message}") }
            }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }
}
