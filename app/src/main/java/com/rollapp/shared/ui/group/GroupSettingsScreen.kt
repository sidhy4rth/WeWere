package com.rollapp.shared.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.Sharing

@Composable
fun GroupSettingsScreen(
    onBack: () -> Unit,
    onOpenMembers: (String) -> Unit,
    onLeftGroup: () -> Unit,
    viewModel: GroupSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var nameDraft by remember(state.group?.name) { mutableStateOf(state.group?.name.orEmpty()) }
    var descriptionDraft by remember(state.group?.description) {
        mutableStateOf(state.group?.description.orEmpty())
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::updateCover) }

    LaunchedEffect(state.finished) {
        if (state.finished) onLeftGroup()
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Edit group") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        label = { Text("Group name") }
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = descriptionDraft,
                        onValueChange = { descriptionDraft = it },
                        label = { Text("Description") },
                        placeholder = { Text("Optional") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(nameDraft)
                        viewModel.updateDescription(descriptionDraft)
                        renaming = false
                    },
                    enabled = nameDraft.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this group?") },
            text = {
                Text(
                    "You'll lose access to its photos. Photos you uploaded stay with the " +
                        "group for everyone else."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.leaveGroup(); confirmLeave = false }) {
                    Text("Leave")
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${state.group?.name}\"?") },
            text = {
                Text(
                    "Every photo in this group is permanently deleted for all " +
                        "${state.group?.memberCount ?: 0} members. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteGroup(); confirmDelete = false }) {
                    Text("Delete everything")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
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

            val group = state.group

            SettingsRow(
                icon = Icons.Rounded.People,
                title = "Members",
                subtitle = "${group?.memberCount ?: 0} in this group",
                onClick = { onOpenMembers(viewModel.groupId) }
            )

            if (group != null && group.isInviteActive) {
                SettingsRow(
                    icon = Icons.Rounded.Link,
                    title = "Invite code",
                    subtitle = group.inviteCode,
                    trailing = Icons.Rounded.ContentCopy,
                    onClick = {
                        Sharing.copyToClipboard(context, "Invite code", group.inviteCode)
                        Sharing.shareInvite(context, group.name, group.inviteCode, group.inviteLink)
                    }
                )
            }

            if (state.isAdmin) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsRow(
                    icon = Icons.Rounded.Image,
                    title = "Change cover photo",
                    onClick = {
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                SettingsRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Name and description",
                    subtitle = group?.name,
                    onClick = {
                        nameDraft = group?.name.orEmpty()
                        descriptionDraft = group?.description.orEmpty()
                        renaming = true
                    }
                )
                SettingsRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Generate a new code",
                    subtitle = "The old link stops working immediately",
                    onClick = { viewModel.regenerateInvite(null) }
                )
                if (group?.isInviteActive == true) {
                    SettingsRow(
                        icon = Icons.Rounded.LinkOff,
                        title = "Turn off invites",
                        subtitle = "Nobody new can join until you make a new code",
                        onClick = viewModel::revokeInvite
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingsRow(
                icon = Icons.Rounded.Logout,
                title = "Leave group",
                destructive = true,
                onClick = { confirmLeave = true }
            )

            if (state.isAdmin) {
                SettingsRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = "Delete group",
                    subtitle = "Removes every photo for everyone",
                    destructive = true,
                    onClick = { confirmDelete = true }
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: ImageVector = Icons.Rounded.ChevronRight,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = tint)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            trailing,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
