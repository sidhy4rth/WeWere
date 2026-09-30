package com.rollapp.shared.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.domain.repository.PhotoRepository
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CalendarUiState(
    val loading: Boolean = true,
    val byDay: Map<LocalDate, Photo> = emptyMap(),
    val months: List<YearMonth> = emptyList()
)

/** Every roll you're in, as one calendar. Loaded once; the grouping runs off the main thread. */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val photos: PhotoRepository
) : ViewModel() {
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val all = runCatching {
                val mine = groups.observeMyGroups().first()
                mine.map { g -> async { photos.fetchAllPhotos(g.id).dataOrNull.orEmpty() } }.awaitAll().flatten()
            }.getOrDefault(emptyList())

            _state.value = withContext(Dispatchers.Default) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                // The day in one frame: most-starred photo, newest wins a tie.
                val byDay = all
                    .groupBy { Instant.ofEpochMilli(it.capturedAt ?: it.createdAt).atZone(zone).toLocalDate() }
                    .mapValues { (_, day) -> day.maxWith(compareBy<Photo>({ it.favoritedBy.size }, { it.createdAt })) }
                val first = byDay.keys.minOrNull()?.let(YearMonth::from) ?: YearMonth.from(today)
                val months = generateSequence(YearMonth.from(today)) { it.minusMonths(1) }
                    .takeWhile { !it.isBefore(first) }.toList()
                CalendarUiState(loading = false, byDay = byDay, months = months)
            }
        }
    }
}

@Composable
fun CalendarScreen(
    onOpenPhoto: (groupId: String, photoId: String) -> Unit,
    onAddToday: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    Box(Modifier.fillMaxSize().padding(0.dp)) {
        if (state.loading) {
            CircularProgressIndicator(color = Gold, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
            ) {
                item(key = "title") {
                    Text("Calendar", style = MaterialTheme.typography.headlineLarge, color = Ivory,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
                }
                items(state.months, key = { it.toString() }) { month ->
                    MonthCard(month, state.byDay, today, onOpenPhoto = { onOpenPhoto(it.groupId, it.id) }, onAddToday = onAddToday)
                }
            }
        }
    }
}
