package com.rollapp.shared.ui.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.Member
import com.rollapp.shared.domain.repository.AuthRepository
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

data class MembersUiState(
    val group: Group? = null,
    val members: List<Member> = emptyList(),
    val myUid: String? = null,
    val isAdmin: Boolean = false,
    val error: AppError? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class MembersViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])
    private val errorFlow = MutableStateFlow<AppError?>(null)

    val state: StateFlow<MembersUiState> = combine(
        groupRepository.observeGroup(groupId),
        groupRepository.observeMembers(groupId),
        groupRepository.observeMembership(groupId),
        errorFlow
    ) { group, members, membership, error ->
        MembersUiState(
            group = group,
            // Most photos first — on a trip this is genuinely the interesting ranking.
            members = members.sortedByDescending { it.photoCount },
            myUid = authRepository.currentUid(),
            isAdmin = membership?.isAdmin == true,
            error = error,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembersUiState())

    fun removeMember(uid: String) {
        viewModelScope.launch {
            when (val outcome = groupRepository.removeMember(groupId, uid)) {
                is Outcome.Failure -> errorFlow.value = outcome.error
                is Outcome.Success -> Unit
            }
        }
    }

    fun regenerateInvite() {
        viewModelScope.launch {
            when (val outcome = groupRepository.regenerateInviteCode(groupId, expiresAt = null)) {
                is Outcome.Failure -> errorFlow.value = outcome.error
                is Outcome.Success -> Unit
            }
        }
    }

    fun dismissError() {
        errorFlow.value = null
    }
}
