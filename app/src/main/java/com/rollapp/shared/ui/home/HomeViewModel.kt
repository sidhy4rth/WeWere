package com.rollapp.shared.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.PendingUpload
import com.rollapp.shared.domain.model.UploadState
import com.rollapp.shared.domain.model.User
import com.rollapp.shared.domain.repository.AuthRepository
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.domain.repository.UploadQueueRepository
import com.rollapp.shared.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val user: User? = null,
    val groups: List<Group> = emptyList(),
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val isLoading: Boolean = true
) {
    val isEmpty: Boolean get() = !isLoading && groups.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    userRepository: UserRepository,
    groupRepository: GroupRepository,
    private val uploadQueueRepository: UploadQueueRepository
) : ViewModel() {

    /**
     * The profile document rather than the auth user: a name or avatar changed on
     * another device should show up here without a re-login.
     */
    private val userFlow = authRepository.currentUser.flatMapLatest { authUser ->
        if (authUser == null) flowOf(null) else userRepository.observeUser(authUser.uid)
    }

    val state: StateFlow<HomeUiState> = combine(
        userFlow,
        groupRepository.observeMyGroups(),
        uploadQueueRepository.observePending()
    ) { user, groups, pending ->
        HomeUiState(
            user = user,
            groups = groups,
            pendingCount = pending.count { it.state != UploadState.FAILED },
            failedCount = pending.count { it.state == UploadState.FAILED },
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        // Keep listeners alive briefly across a rotation, but drop them when the
        // user actually leaves — idle Firestore listeners cost reads.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    fun retryFailedUploads() {
        viewModelScope.launch { uploadQueueRepository.retryAllFailed() }
    }
}
