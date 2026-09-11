package com.rollapp.shared.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rollapp.shared.ui.components.GoldButton
import com.rollapp.shared.ui.components.HairlineButton
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.PrintStack
import com.rollapp.shared.ui.components.QuietButton
import com.rollapp.shared.ui.components.RiseIn
import com.rollapp.shared.ui.components.rollFieldColors
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.IvoryMuted
import com.rollapp.shared.ui.theme.Muted

@Composable
fun AuthScreen(
    onSignedIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordVisible by remember { mutableStateOf(false) }
    // Google is the front door; the email form only unfolds when asked for.
    var emailFormOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    LaunchedEffect(state.passwordResetSent) {
        if (state.passwordResetSent) {
            snackbarHostState.showSnackbar("Check your inbox for a reset link")
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to Color(0xFF1A1712),
                        1f to Ink,
                        center = Offset(0.5f, 0f),
                        radius = 1400f
                    )
                )
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = !emailFormOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                PrintStack(modifier = Modifier.fillMaxWidth().height(360.dp))
            }

            if (emailFormOpen) Spacer(Modifier.height(72.dp))

            RiseIn(delayMillis = 500) {
                Text(
                    text = "WeWere",
                    style = MaterialTheme.typography.displayLarge,
                    color = Gold
                )
            }
            Spacer(Modifier.height(10.dp))
            RiseIn(delayMillis = 600) { Hairline(Modifier.width(120.dp)) }
            Spacer(Modifier.height(10.dp))
            RiseIn(delayMillis = 650) {
                Text(
                    text = "One shared camera roll for the people you were with.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IvoryMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 56.dp)
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.error?.let { error ->
                    InlineError(message = error.message ?: "Something went wrong")
                }

                AnimatedVisibility(
                    visible = emailFormOpen,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    EmailForm(
                        state = state,
                        passwordVisible = passwordVisible,
                        onTogglePassword = { passwordVisible = !passwordVisible },
                        viewModel = viewModel
                    )
                }

                if (!emailFormOpen) {
                    RiseIn(delayMillis = 780) {
                        GoldButton(
                            text = "Continue with Google",
                            onClick = { viewModel.signInWithGoogle(context) },
                            enabled = !state.isSubmitting,
                            loading = state.isSubmitting
                        )
                    }
                    RiseIn(delayMillis = 860) {
                        HairlineButton(
                            text = "Use email instead",
                            onClick = { emailFormOpen = true },
                            enabled = !state.isSubmitting
                        )
                    }
                    RiseIn(delayMillis = 940) {
                        QuietButton(
                            text = "Just looking — continue as guest",
                            onClick = viewModel::continueAsGuest,
                            enabled = !state.isSubmitting
                        )
                    }
                } else {
                    QuietButton(
                        text = "Back to Google sign-in",
                        onClick = { emailFormOpen = false },
                        enabled = !state.isSubmitting
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun EmailForm(
    state: AuthUiState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    viewModel: AuthViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(visible = state.mode == AuthMode.SIGN_UP) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Your name") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                colors = rollFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text("Email") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = rollFieldColors(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = rollFieldColors(),
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            trailingIcon = {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff
                        else Icons.Rounded.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = Muted
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (state.mode == AuthMode.SIGN_IN) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = viewModel::sendPasswordReset,
                    enabled = state.email.contains("@")
                ) {
                    Text("Forgot password?", style = MaterialTheme.typography.bodySmall, color = Gold)
                }
            }
        }

        GoldButton(
            text = if (state.mode == AuthMode.SIGN_IN) "Sign in" else "Create account",
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            loading = state.isSubmitting
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (state.mode == AuthMode.SIGN_IN) "New here?" else "Already have an account?",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            TextButton(
                onClick = {
                    viewModel.setMode(
                        if (state.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                    )
                }
            ) {
                Text(
                    if (state.mode == AuthMode.SIGN_IN) "Create an account" else "Sign in",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gold
                )
            }
        }
    }
}
