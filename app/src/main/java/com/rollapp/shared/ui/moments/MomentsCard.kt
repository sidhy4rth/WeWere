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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
@Composable
fun MomentsCard(
    group: Group,
    onGoExclusive: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MomentsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }

    val recorder = rememberLauncherForActivityResult(RecordFiveSeconds()) { ok ->
        val uri = pendingCapture
        if (ok && uri != null) viewModel.upload(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.dismissMessage() }
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Gold.copy(alpha = 0.25f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Readout("Moments · this week", color = Gold)
                Text(
                    text = when (val n = state.moments.size) {
                        0 -> "No moments yet — record your 5 seconds"
                        1 -> "1 moment from the roll"
                        else -> "$n moments from ${state.moments.map { it.uploadedBy }.distinct().size} people"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = IvoryMuted
                )
            }
        }

        if (state.moments.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.moments.forEach { m ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { play(context, m.videoUrl) }
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, Gold, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${m.uploaderName}'s moment", tint = Gold)
                        }
                        Text(m.uploaderName.substringBefore(" "), style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
                    }
                }
            }
        }

        if (!group.developed) {
            Readout("Weekly montage download · Go Exclusive", color = Muted)
        }

        state.uploadProgress?.let {
            Readout("Uploading moment…", color = Gold)
            LinearProgressIndicator(progress = { it }, color = Gold, modifier = Modifier.fillMaxWidth())
        }
        state.montageStatus?.let {
            Readout(it, color = Gold)
            LinearProgressIndicator(color = Gold, modifier = Modifier.fillMaxWidth())
        }

        // Three equal buttons: they must fit a narrow phone side by side.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Pill(Icons.Rounded.Videocam, "Record ${Limits.MOMENT_SECONDS}s", Modifier.weight(1f)) {
                val file = File(context.cacheDir, "captures/moment-${System.currentTimeMillis()}.mp4").apply { parentFile?.mkdirs() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                pendingCapture = uri
                recorder.launch(uri)
            }
            val reminder = state.reminderMinute
            Pill(Icons.Rounded.Alarm, reminder?.let { "%d:%02d".format(it / 60, it % 60) } ?: "Remind", Modifier.weight(1f)) {
                val start = reminder ?: (20 * 60)
                TimePickerDialog(context, { _, h, min -> viewModel.setReminder(group.name, h * 60 + min) }, start / 60, start % 60, false).show()
            }
            Pill(Icons.Rounded.Movie, "Montage", Modifier.weight(1f)) {
                when {
                    !group.developed -> onGoExclusive()
                    state.moments.isEmpty() -> Toast.makeText(context, "No moments this week yet", Toast.LENGTH_SHORT).show()
                    else -> viewModel.makeMontage(group.name)
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, Gold.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
    ) {
        Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Ivory, maxLines = 1, softWrap = false)
    }
}

private fun play(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/mp4"))
    }.onFailure { Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show() }
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
