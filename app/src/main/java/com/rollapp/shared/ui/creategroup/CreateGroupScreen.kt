package com.rollapp.shared.ui.creategroup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rollapp.shared.core.Limits
import com.rollapp.shared.ui.components.InlineError
import com.rollapp.shared.ui.components.QrCode
import com.rollapp.shared.ui.components.Sharing
import com.rollapp.shared.ui.components.RollTopBar
import com.rollapp.shared.ui.components.rollFieldColors
import com.rollapp.shared.ui.theme.Ink
import androidx.compose.foundation.border
import com.rollapp.shared.ui.components.GoldButton

@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onGroupReady: (String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The photo picker needs no storage permission on any API level.
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onCoverSelected(uri) }

    state.created?.let { group ->
        AlertDialog(
            onDismissRequest = { onGroupReady(group.id) },
            title = { Text("\"${group.name}\" is ready") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Hold this up and your friends can scan it, or send them the code.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    QrCode(content = group.inviteLink, size = 170.dp)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                Sharing.copyToClipboard(context, "Invite code", group.inviteCode)
                            }
                            .padding(horizontal = 28.dp, vertical = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = group.inviteCode,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Copy code",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    Sharing.shareInvite(context, group.name, group.inviteCode, group.inviteLink)
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Invite friends")
                }
            },
            dismissButton = {
                TextButton(onClick = { onGroupReady(group.id) }) { Text("Later") }
            }
        )
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            RollTopBar(title = "New roll", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, com.rollapp.shared.ui.theme.Gold.copy(alpha = 0.35f), MaterialTheme.shapes.large)
                    .clickable {
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (state.coverUri != null) {
                    AsyncImage(
                        model = state.coverUri,
                        contentDescription = "Group cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Add a cover photo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Optional — the first photo works too",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                colors = rollFieldColors(),
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Roll name") },
                placeholder = { Text("Goa Trip 2026") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                supportingText = {
                    if (state.nameRemaining <= 10) Text("${state.nameRemaining} left")
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                colors = rollFieldColors(),
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description") },
                placeholder = { Text("Optional") },
                shape = MaterialTheme.shapes.medium,
                minLines = 2,
                maxLines = 4,
                supportingText = {
                    Text("${state.description.length}/${Limits.MAX_GROUP_DESCRIPTION_LENGTH}")
                },
                modifier = Modifier.fillMaxWidth()
            )

            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                InlineError(message = error.message ?: "Couldn't create the group")
            }

            Spacer(Modifier.height(28.dp))

            GoldButton(
    text = "Create roll",
    onClick = viewModel::create,
    enabled = state.canCreate,
    loading = state.isCreating
)

            Spacer(Modifier.height(32.dp))
        }
    }
}
