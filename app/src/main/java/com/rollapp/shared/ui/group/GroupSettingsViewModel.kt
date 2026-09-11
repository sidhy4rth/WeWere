package com.rollapp.shared.ui.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupSettingsUiState(
    val group: Group? = null,
    val isAdmin: Boolean = false,
    val isSaving: Boolean = false,
    val error: AppError? = null,
    val finished: Boolean = false
)

@HiltViewModel
class GroupSettingsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])

    private val saving = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<AppError?>(null)
    private val finished = MutableStateFlow(false)

    val state: StateFlow<GroupSettingsUiState> = combine(
        groupRepository.observeGroup(groupId),
        groupRepository.observeMembership(groupId),
        saving,
        errorFlow,
        finished
    ) { group, membership, isSaving, error, done ->
        GroupSettingsUiState(
            group = group,
            isAdmin = membership?.isAdmin == true,
            isSaving = isSaving,
            error = error,
            finished = done
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupSettingsUiState())

    fun rename(name: String) = run("rename") {
        groupRepository.updateGroup(groupId, name = name, description = null)
    }

    fun updateDescription(description: String) = run("description") {
        groupRepository.updateGroup(groupId, name = null, description = description)
    }

    fun updateCover(uri: Uri) = run("cover") {
        groupRepository.updateCoverPhoto(groupId, uri)
    }

    fun regenerateInvite(expiresAt: Long?) = run("invite") {
        groupRepository.regenerateInviteCode(groupId, expiresAt)
    }

    fun revokeInvite() = run("revoke") {
        groupRepository.revokeInvite(groupId)
    }

    fun leaveGroup() = run("leave", finishOnSuccess = true) {
        groupRepository.leaveGroup(groupId)
    }

    fun deleteGroup() = run("delete", finishOnSuccess = true) {
        groupRepository.deleteGroup(groupId)
    }

    fun dismissError() {
        errorFlow.value = null
    }

    private fun run(
        @Suppress("UNUSED_PARAMETER") tag: String,
        finishOnSuccess: Boolean = false,
        block: suspend () -> Outcome<*>
    ) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            errorFlow.value = null
            when (val outcome = block()) {
                is Outcome.Success -> if (finishOnSuccess) finished.value = true
                is Outcome.Failure -> errorFlow.value = outcome.error
            }
            saving.value = false
        }
    }
}
