package com.rollapp.shared.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rollapp.shared.core.TimeFormat
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.UserAvatar
import com.rollapp.shared.ui.components.RollTopBar
import com.rollapp.shared.ui.components.rollFieldColors
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.components.GoldButton
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.theme.Gold

@Composable
fun ProfileScreen(
    onSignedOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editingName by remember { mutableStateOf(false) }
    var upgradingGuest by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var nameDraft by remember(state.user?.name) { mutableStateOf(state.user?.name.orEmpty()) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::updatePhoto) }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = {
                OutlinedTextField(
                    colors = rollFieldColors(),
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    supportingText = { Text("This is what your friends see on your photos") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.updateName(nameDraft); editingName = false },
                    enabled = nameDraft.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } }
        )
    }

    if (upgradingGuest) {
        GuestUpgradeDialog(
            onDismiss = { upgradingGuest = false },
            onSubmit = { name, email, password ->
                viewModel.convertGuestToAccount(name, email, password)
                upgradingGuest = false
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "Your profile is deleted and you're removed from your groups. Photos " +
                        "you've already shared stay with those groups."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAccount(); confirmDelete = false }) {
                    Text("Delete account")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = Ink,
        topBar = { RollTopBar(title = "You", onBack = null) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            state.error?.let { error ->
                InlineError(
                    message = error.message ?: "Something went wrong",
                    actionLabel = "Dismiss",
                    onAction = viewModel::dismissError,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    UserAvatar(
                        name = state.user?.name.orEmpty(),
                        photoUrl = state.user?.photoUrl,
                        seed = state.user?.uid.orEmpty(),
                        size = 96.dp,
                        borderColor = Gold,
                        modifier = Modifier.clickable {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { nameDraft = state.user?.name.orEmpty(); editingName = true }
                ) {
                    Text(
                        text = state.user?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Edit name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(18.dp)
                    )
                }
                state.user?.email?.takeIf { it.isNotBlank() }?.let { email ->
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.user?.createdAt?.takeIf { it > 0 }?.let { created ->
                    Spacer(Modifier.height(6.dp))
                    Readout(text = "On WeWere since ${TimeFormat.absoluteDate(created)}", color = Gold)
                }
            }

            if (state.user?.isAnonymous == true) {
                GoldButton(
                    text = "Save your account",
                    onClick = { upgradingGuest = true },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    text = "You're signed in as a guest. Add an email so you don't lose " +
                        "your groups if you change phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            Hairline(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))

            Readout(
                text = "Notifications",
                color = Gold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            PrefSwitch(
                label = "New photos",
                subtitle = "When friends add photos to your groups",
                checked = state.prefs.newPhotos,
                onChange = { viewModel.updatePrefs(state.prefs.copy(newPhotos = it)) }
            )
            PrefSwitch(
                label = "Reactions",
                subtitle = "When someone reacts to your photo",
                checked = state.prefs.reactions,
                onChange = { viewModel.updatePrefs(state.prefs.copy(reactions = it)) }
            )
            PrefSwitch(
                label = "People joining",
                subtitle = "When someone new joins a group",
                checked = state.prefs.memberJoined,
                onChange = { viewModel.updatePrefs(state.prefs.copy(memberJoined = it)) }
            )

            Hairline(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))

            TextButton(
                onClick = viewModel::signOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) { Text("Sign out") }

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Text("Delete account", color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PrefSwitch(
    label: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GuestUpgradeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save your account") },
        text = {
            Column {
                Text(
                    text = "Your groups and photos stay exactly as they are.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    colors = rollFieldColors(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = rollFieldColors(),
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = rollFieldColors(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name, email, password) },
                enabled = name.isNotBlank() && email.contains("@") && password.length >= 6
            ) { Text("Save account") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}
