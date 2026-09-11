package com.rollapp.shared.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rollapp.shared.core.TimeFormat
import com.rollapp.shared.domain.model.ActivityEvent
import com.rollapp.shared.domain.model.ActivityType
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.ui.components.EmptyState
import com.rollapp.shared.ui.components.UserAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ActivityViewModel @Inject constructor(
    groupRepository: GroupRepository
) : ViewModel() {

    val events: StateFlow<List<ActivityEvent>> = groupRepository.observeActivity(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ActivityScreen(
    onOpenGroup: (String) -> Unit,
    viewModel: ActivityViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Activity") }) }
    ) { padding ->
        if (events.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Notifications,
                title = "Nothing yet",
                body = "New photos, reactions and people joining your groups show up here.",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(events, key = { "${it.groupId}-${it.id}" }) { event ->
                    ActivityRow(event = event, onClick = { onOpenGroup(event.groupId) })
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = event.actorName,
            photoUrl = event.actorPhotoUrl,
            seed = event.actorId,
            size = 40.dp
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = describe(event),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = TimeFormat.relative(event.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!event.previewPhotoUrl.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            AsyncImage(
                model = event.previewPhotoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(MaterialTheme.shapes.small)
            )
        }
    }
}

private fun describe(event: ActivityEvent): String = when (event.type) {
    ActivityType.PHOTOS_ADDED -> {
        val count = event.photoCount.coerceAtLeast(1)
        val noun = if (count == 1) "a photo" else "$count photos"
        "${event.actorName} added $noun to ${event.groupName}"
    }
    ActivityType.MEMBER_JOINED -> "${event.actorName} joined ${event.groupName}"
    ActivityType.MEMBER_LEFT -> "${event.actorName} left ${event.groupName}"
    ActivityType.REACTION -> "${event.actorName} reacted ${event.reactionKey.orEmpty()} to your photo"
    ActivityType.GROUP_CREATED -> "${event.actorName} created ${event.groupName}"
    ActivityType.UNKNOWN -> "${event.actorName} did something in ${event.groupName}"
}
