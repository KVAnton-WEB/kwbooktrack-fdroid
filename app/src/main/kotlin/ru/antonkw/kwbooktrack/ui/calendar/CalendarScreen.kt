package ru.antonkw.kwbooktrack.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import ru.antonkw.kwbooktrack.ui.common.MonthYearPickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val mediaType by viewModel.mediaType.collectAsStateWithLifecycle()
    // Locale of the app (may be overridden per-app), not of the device:
    // a hardcoded locale here printed "ноября" in an English UI.
    val locale = LocalContext.current.resources.configuration.locales.get(0)
    val today = remember { LocalDate.now() }
    val maxYm = remember(today) { YearMonth.of(today.year, today.month) }

    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.calendar_prompt),
                style = MaterialTheme.typography.bodyMedium,
            )

            CalendarModeSelector(
                mode = mode,
                isMovies = mediaType == MediaType.MOVIES,
                onModeChange = viewModel::setMode,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = viewModel::goToPreviousMonth,
                    enabled = true,
                ) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.calendar_prev_month))
                }
                TextButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = month.format(
                            remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                IconButton(
                    onClick = viewModel::goToNextMonth,
                    enabled = month < maxYm,
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.calendar_next_month),
                        tint = if (month < maxYm) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                }
            }
            TextButton(
                onClick = { viewModel.goToMonth(maxYm) },
                enabled = month < maxYm,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.calendar_today)) }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val weekdays = stringArrayResource(R.array.weekdays_short)
                weekdays.forEachIndexed { idx, label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize().padding(top = 4.dp),
            ) {
                // Key on the date string so scroll state and any per-cell state survive month
                // changes — without it, swapping the underlying days list leaves
                // the grid thinking item N at index N when in fact the date moved.
                items(
                    count = uiState.days.size,
                    key = { idx -> uiState.days[idx]?.date?.toString() ?: "pad_$idx" },
                ) { idx ->
                    val day = uiState.days[idx]
                    DayCell(day = day, mode = mode, isMovies = mediaType == MediaType.MOVIES)
                }
            }
        }
    }

    if (showDatePicker) {
        MonthYearPickerDialog(
            initial = month,
            max = maxYm,
            onDismiss = { showDatePicker = false },
            onConfirm = { ym ->
                viewModel.goToMonth(ym)
                showDatePicker = false
            },
        )
    }
}

// Режимы ячейки календаря — список создаётся один раз на процесс, а не на
// каждой рекомпозиции.
private val CALENDAR_MODES = listOf(CalendarMode.COVERS, CalendarMode.VALUE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarModeSelector(
    mode: CalendarMode,
    isMovies: Boolean,
    onModeChange: (CalendarMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        CALENDAR_MODES.forEachIndexed { index, m ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = CALENDAR_MODES.size),
                selected = m == mode,
                onClick = { onModeChange(m) },
                icon = {},
            ) {
                Text(
                    text = stringResource(
                        when (m) {
                            CalendarMode.COVERS -> R.string.calendar_mode_covers
                            // Второй режим — величина: страницы у книг, минуты у фильмов.
                            CalendarMode.VALUE -> if (isMovies) {
                                R.string.calendar_mode_minutes
                            } else {
                                R.string.calendar_mode_pages
                            }
                        }
                    ),
                )
            }
        }
    }
}

@Composable
private fun DayCell(day: CalendarDay?, mode: CalendarMode, isMovies: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(2.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        if (day == null) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(2.dp),
            )
            if (mode == CalendarMode.VALUE) {
                // Второй режим — величина: страницы у книг, минуты у фильмов.
                val value = if (isMovies) day.minutes else day.pages
                if (value > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else {
                when {
                    day.books.isEmpty() -> {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.fillMaxSize())
                    }
                    day.books.size == 1 -> {
                        val book = day.books.first()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true)
                                .padding(vertical = 1.dp),
                        ) {
                            CoverTile(book, fillHeight = true)
                        }
                    }
                    else -> {
                        val perTileHeight = (110.dp / day.books.size.coerceAtMost(3))
                        day.books.take(3).forEach { book ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(perTileHeight)
                                    .padding(vertical = 1.dp),
                            ) {
                                CoverTile(book, fillHeight = true)
                            }
                        }
                    }
                }
                if (day.books.isNotEmpty() && day.books.first().rating != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        repeat(day.books.first().rating!!) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(book: CalendarBook, fillHeight: Boolean) {
    // AsyncImage handles a missing/invalid file path by going to the error
    // branch itself — no need for a File.exists() check on the UI thread here.
    if (!book.coverPath.isNullOrBlank()) {
        AsyncImage(
            model = File(book.coverPath),
            contentDescription = book.title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.title.take(8),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.items(count: Int, itemContent: @Composable (Int) -> Unit) {
    repeat(count) { idx -> item { itemContent(idx) } }
}
