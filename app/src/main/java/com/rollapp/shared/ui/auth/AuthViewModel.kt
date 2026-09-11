package com.rollapp.shared.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.core.map
import com.rollapp.shared.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, SIGN_UP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    val passwordResetSent: Boolean = false,
    val signedIn: Boolean = false
) {
    val canSubmit: Boolean
        get() = !isSubmitting &&
            email.contains("@") &&
            password.length >= 6 &&
            (mode == AuthMode.SIGN_IN || name.isNotBlank())
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val googleSignInHelper: GoogleSignInHelper
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun setMode(mode: AuthMode) = _state.update { it.copy(mode = mode, error = null) }
    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }

            val outcome = when (current.mode) {
                AuthMode.SIGN_IN -> authRepository.signInWithEmail(current.email, current.password)
                AuthMode.SIGN_UP -> authRepository.signUpWithEmail(
                    current.name, current.email, current.password
                )
            }
            applyOutcome(outcome.map { })
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }

            when (val tokenOutcome = googleSignInHelper.getIdToken(context)) {
                is Outcome.Failure ->
                    _state.update { it.copy(isSubmitting = false, error = tokenOutcome.error) }

                is Outcome.Success ->
                    applyOutcome(authRepository.signInWithGoogle(tokenOutcome.data).map { })
            }
        }
    }

    fun continueAsGuest() {
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            applyOutcome(authRepository.signInAnonymously().map { })
        }
    }

    fun sendPasswordReset() {
        val email = _state.value.email
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val outcome = authRepository.sendPasswordReset(email)) {
                is Outcome.Success ->
                    _state.update { it.copy(isSubmitting = false, passwordResetSent = true) }
                is Outcome.Failure ->
                    _state.update { it.copy(isSubmitting = false, error = outcome.error) }
            }
        }
    }

    private fun applyOutcome(outcome: Outcome<Unit>) {
        _state.update {
            when (outcome) {
                is Outcome.Success -> it.copy(isSubmitting = false, signedIn = true)
                is Outcome.Failure -> it.copy(isSubmitting = false, error = outcome.error)
            }
        }
    }
}
