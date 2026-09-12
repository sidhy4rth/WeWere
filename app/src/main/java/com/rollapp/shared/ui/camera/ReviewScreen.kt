package com.rollapp.shared.ui.camera

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rollapp.shared.ui.components.InlineError

@Composable
fun ReviewScreen(
    onRetake: () -> Unit,
    onShared: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.shared) {
        if (state.shared) onShared()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = viewModel.photoUri,
            contentDescription = "Photo you just took",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = state.caption,
                onValueChange = viewModel::onCaptionChange,
                placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.6f)) },
                shape = MaterialTheme.shapes.medium,
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color.White
                ),
                supportingText = {
                    if (state.captionRemaining <= 20) {
                        Text("${state.captionRemaining} left", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            state.error?.let { error ->
                Spacer(Modifier.height(10.dp))
                InlineError(message = error.message ?: "Couldn't queue that photo")
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onRetake,
                    enabled = !state.isSubmitting,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text("Retake", color = Color.White)
                }

                Button(
                    onClick = viewModel::shareToGroup,
                    enabled = !state.isSubmitting,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(52.dp)
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Share to roll", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
