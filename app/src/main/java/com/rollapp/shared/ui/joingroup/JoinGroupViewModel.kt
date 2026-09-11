package com.rollapp.shared.ui.joingroup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Limits
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.data.remote.InviteCodes
import com.rollapp.shared.domain.model.GroupPreview
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinGroupUiState(
    val code: String = "",
    val preview: GroupPreview? = null,
    val isLookingUp: Boolean = false,
    val isJoining: Boolean = false,
    val error: AppError? = null,
    val joinedGroupId: String? = null
) {
    val canLookUp: Boolean
        get() = !isLookingUp && !isJoining && code.length >= Limits.INVITE_CODE_LENGTH
}

@HiltViewModel
class JoinGroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(JoinGroupUiState())
    val state: StateFlow<JoinGroupUiState> = _state.asStateFlow()

    init {
        // Arriving from an invite link: fill the field and look it up straight away,
        // so the user lands on "You've been invited to…" rather than an empty form.
        savedStateHandle.get<String>(NavArgs.CODE)
            ?.let(InviteCodes::normalise)
            ?.takeIf { it.isNotBlank() }
            ?.let { prefilled ->
                _state.update { it.copy(code = prefilled) }
                lookUp()
            }
    }

    fun onCodeChange(value: String) {
        val normalised = InviteCodes.normalise(value).take(Limits.INVITE_CODE_LENGTH + 3)
        _state.update { it.copy(code = normalised, error = null, preview = null) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun clearPreview() = _state.update { it.copy(preview = null) }

    fun lookUp() {
        val code = _state.value.code
        if (code.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isLookingUp = true, error = null) }

            when (val outcome = groupRepository.previewByInviteCode(code)) {
                is Outcome.Success ->
                    _state.update { it.copy(isLookingUp = false, preview = outcome.data) }
                is Outcome.Failure ->
                    _state.update { it.copy(isLookingUp = false, error = outcome.error) }
            }
        }
    }

    fun join() {
        val code = _state.value.code
        if (code.isBlank() || _state.value.isJoining) return

        viewModelScope.launch {
            _state.update { it.copy(isJoining = true, error = null) }

            when (val outcome = groupRepository.joinByInviteCode(code)) {
                is Outcome.Success ->
                    _state.update { it.copy(isJoining = false, joinedGroupId = outcome.data.id) }
                is Outcome.Failure ->
                    _state.update { it.copy(isJoining = false, error = outcome.error) }
            }
        }
    }
}
