package com.rollapp.shared.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.Raised
import com.rollapp.shared.ui.theme.Surface
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The roll as a calendar: one card per month, one photo per day. The day's photo is
 * the one the group starred most (newest wins a tie) — the whole day in a single
 * frame. Days without photos are dots; today is a gold "+" that opens the camera.
 */
@Composable
fun CalendarSheet(
    loadPhotos: suspend () -> List<Photo>,
    onOpenPhoto: (Photo) -> Unit,
    onAddToday: () -> Unit,
    onDismiss: () -> Unit
) {
    var photos by remember { mutableStateOf<List<Photo>?>(null) }
    LaunchedEffect(Unit) { photos = runCatching { loadPhotos() }.getOrDefault(emptyList()) }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay: Map<LocalDate, Photo> = remember(photos) {
        photos.orEmpty()
            .groupBy { Instant.ofEpochMilli(it.capturedAt ?: it.createdAt).atZone(zone).toLocalDate() }
            .mapValues { (_, day) -> day.maxWith(compareBy<Photo>({ it.favoritedBy.size }, { it.createdAt })) }
    }
    // Every month from the roll's first photo to now, newest first.
    val months: List<YearMonth> = remember(byDay) {
        val first = byDay.keys.minOrNull()?.let(YearMonth::from) ?: YearMonth.from(today)
        generateSequence(YearMonth.from(today)) { it.minusMonths(1) }.takeWhile { !it.isBefore(first) }.toList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink,
        scrimColor = Ink.copy(alpha = 0.7f),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 3.dp).clip(CircleShape).background(Gold.copy(alpha = 0.4f)))
        }
    ) {
        if (photos == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                items(months, key = { it.toString() }) { month ->
                    MonthCard(month, byDay, today, onOpenPhoto, onAddToday)
                }
            }
        }
    }
}

@Composable
internal fun MonthCard(
    month: YearMonth,
    byDay: Map<LocalDate, Photo>,
    today: LocalDate,
    onOpenPhoto: (Photo) -> Unit,
    onAddToday: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Surface)
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Text(
            "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
            style = MaterialTheme.typography.headlineSmall,
            color = Ivory,
            modifier = Modifier.padding(start = 6.dp, bottom = 14.dp)
        )
        // Weeks start on Monday; blank cells pad the first week.
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 7).forEach { i ->
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        when {
                            date == null -> Unit
                            date == today -> Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .border(2.5.dp, Gold, RoundedCornerShape(12.dp)).clickable(onClick = onAddToday),
                                contentAlignment = Alignment.Center
                            ) {
                                val photo = byDay[date]
                                if (photo != null) {
                                    AsyncImage(photo.thumbnailUrl.ifBlank { photo.imageUrl }, "Today",
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
                                } else {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add a photo today", tint = Gold)
                                }
                            }
                            byDay[date] != null -> AsyncImage(
                                model = byDay.getValue(date).thumbnailUrl.ifBlank { byDay.getValue(date).imageUrl },
                                contentDescription = "Photo from $date",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenPhoto(byDay.getValue(date)) }
                            )
                            else -> Box(Modifier.size(9.dp).clip(CircleShape).background(Raised.copy(alpha = 1f)).border(0.5.dp, Ivory.copy(alpha = 0.08f), CircleShape))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
