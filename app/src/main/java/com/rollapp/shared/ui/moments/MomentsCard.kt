package com.rollapp.shared.ui.moments

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import coil.request.videoFrameMillis
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rollapp.shared.core.Limits
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.IvoryMuted
import com.rollapp.shared.ui.theme.Muted
import java.io.File

/**
 * Moments: everyone in the roll records 5 seconds of what they're doing, nudged by a
 * daily reminder at the time they pick. At the end of the week the clips become one
 * montage — downloading it is a Go Exclusive perk.
 */
/** Launches the 5-second recorder and uploads the result. Used by the bottom bar. */
@Composable
fun rememberMomentRecorder(viewModel: MomentsViewModel = hiltViewModel()): () -> Unit {
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val recorder = rememberLauncherForActivityResult(RecordFiveSeconds()) { ok ->
        val uri = pendingCapture
        if (ok && uri != null) viewModel.upload(uri)
    }
    return {
        val file = File(context.cacheDir, "captures/moment-${System.currentTimeMillis()}.mp4").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCapture = uri
        recorder.launch(uri)
    }
}

/**
 * The Moments strip: icons only. One gold ring per clip this week (tap to play),
 * then the reminder bell and the montage. Recording lives in the bottom bar.
 */
@Composable
fun MomentsCard(
    group: Group,
    onGoExclusive: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MomentsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.dismissMessage() }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.weight(1f))
            val reminder = state.reminderMinute
            Circle(
                icon = Icons.Rounded.Alarm,
                contentDescription = reminder?.let { "Reminder at %d:%02d".format(it / 60, it % 60) } ?: "Set a daily reminder",
                filled = reminder != null
            ) {
                val start = reminder ?: (20 * 60)
                TimePickerDialog(context, { _, h, min -> viewModel.setReminder(group.name, h * 60 + min) }, start / 60, start % 60, false).show()
            }
            Circle(
                icon = Icons.Rounded.Movie,
                contentDescription = if (group.developed) "Make this week's montage" else "Weekly montage (Go Exclusive)",
                filled = false
            ) {
                when {
                    !group.developed -> onGoExclusive()
                    state.moments.isEmpty() -> Toast.makeText(context, "No moments this week yet", Toast.LENGTH_SHORT).show()
                    else -> viewModel.makeMontage(group.name)
                }
            }
        }

        state.uploadProgress?.let {
            LinearProgressIndicator(progress = { it }, color = Gold, modifier = Modifier.fillMaxWidth())
        }
        if (state.montageStatus != null) {
            LinearProgressIndicator(color = Gold, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Circle(icon: ImageVector, contentDescription: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(Gold.copy(alpha = 0.18f)) else Modifier)
            .border(1.dp, Gold.copy(alpha = if (filled) 0.9f else 0.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Gold, modifier = Modifier.size(18.dp))
    }
}

fun playMoment(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/mp4"))
    }.onFailure { Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show() }
}

/** A moment in the roll's grid: a frame from the clip, a play mark, who shot it. */
@Composable
fun MomentTile(moment: com.rollapp.shared.domain.model.Moment, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(com.rollapp.shared.ui.theme.Raised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(LocalContext.current)
                .data(moment.videoUrl)
                .videoFrameMillis(1000)
                .build(),
            contentDescription = "${moment.uploaderName}'s moment",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(com.rollapp.shared.ui.theme.Ink.copy(alpha = 0.55f))
                .border(1.dp, Gold, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
        }
        Text(
            moment.uploaderName.substringBefore(" ") + " · 5s",
            style = MaterialTheme.typography.labelSmall,
            color = Ivory,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
        )
    }
}

/** The phone's own camera app, capped at 5 seconds, writing into our file. */
private class RecordFiveSeconds : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri) = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, input)
        .putExtra(MediaStore.EXTRA_DURATION_LIMIT, Limits.MOMENT_SECONDS)
        .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
}
