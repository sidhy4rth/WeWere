package com.rollapp.shared.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.User
import com.rollapp.shared.domain.repository.AuthRepository
import com.rollapp.shared.domain.repository.NotificationPrefs
import com.rollapp.shared.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val prefs: NotificationPrefs = NotificationPrefs(),
    val isSaving: Boolean = false,
    val error: AppError? = null,
    val signedOut: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val saving = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<AppError?>(null)
    private val signedOut = MutableStateFlow(false)

    val state: StateFlow<ProfileUiState> = combine(
        authRepository.currentUser.flatMapLatest { auth ->
            if (auth == null) flowOf(null) else userRepository.observeUser(auth.uid)
        },
        userRepository.observeNotificationPrefs(),
        saving,
        errorFlow,
        signedOut
    ) { user, prefs, isSaving, error, out ->
        ProfileUiState(user, prefs, isSaving, error, out)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun updateName(name: String) = run { userRepository.updateDisplayName(name) }
    fun updatePhoto(uri: Uri) = run { userRepository.updateProfilePhoto(uri) }
    fun updatePrefs(prefs: NotificationPrefs) = run { userRepository.updateNotificationPrefs(prefs) }

    /**
     * Upgrades a guest account in place. The uid is preserved by Firebase's credential
     * linking, so every group the guest already joined comes with them — which is the
     * only reason guest onboarding is worth offering at all.
     */
    fun convertGuestToAccount(name: String, email: String, password: String) = run {
        authRepository.linkAnonymousToEmail(name, email, password)
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            signedOut.value = true
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            saving.value = true
            when (val outcome = authRepository.deleteAccount()) {
                is Outcome.Success -> signedOut.value = true
                is Outcome.Failure -> errorFlow.value = outcome.error
            }
            saving.value = false
        }
    }

    fun dismissError() {
        errorFlow.value = null
    }

    private fun run(block: suspend () -> Outcome<*>) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            errorFlow.value = null
            (block() as? Outcome.Failure)?.let { errorFlow.value = it.error }
            saving.value = false
        }
    }
}
