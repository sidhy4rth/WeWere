package com.rollapp.shared.ui.creategroup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Limits
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateGroupUiState(
    val name: String = "",
    val description: String = "",
    val coverUri: Uri? = null,
    val isCreating: Boolean = false,
    val error: AppError? = null,
    val created: Group? = null
) {
    val canCreate: Boolean get() = !isCreating && name.isNotBlank()
    val nameRemaining: Int get() = Limits.MAX_GROUP_NAME_LENGTH - name.length
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state: StateFlow<CreateGroupUiState> = _state.asStateFlow()

    fun onNameChange(value: String) {
        if (value.length > Limits.MAX_GROUP_NAME_LENGTH) return
        _state.update { it.copy(name = value, error = null) }
    }

    fun onDescriptionChange(value: String) {
        if (value.length > Limits.MAX_GROUP_DESCRIPTION_LENGTH) return
        _state.update { it.copy(description = value) }
    }

    fun onCoverSelected(uri: Uri?) = _state.update { it.copy(coverUri = uri) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun create() {
        val current = _state.value
        if (!current.canCreate) return

        viewModelScope.launch {
            _state.update { it.copy(isCreating = true, error = null) }

            when (val outcome = groupRepository.createGroup(
                name = current.name,
                description = current.description.takeIf { it.isNotBlank() },
                coverUri = current.coverUri
            )) {
                is Outcome.Success ->
                    _state.update { it.copy(isCreating = false, created = outcome.data) }
                is Outcome.Failure ->
                    _state.update { it.copy(isCreating = false, error = outcome.error) }
            }
        }
    }
}
