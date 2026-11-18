package ru.antonkw.kwbooktrack.ui.library

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.text.Isbn
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.ui.common.CoverImage
import ru.antonkw.kwbooktrack.ui.common.formatDuration
import ru.antonkw.kwbooktrack.ui.common.FilterDropdown
import ru.antonkw.kwbooktrack.ui.common.MonthYearPickerDialog
import ru.antonkw.kwbooktrack.ui.common.PeriodOption
import ru.antonkw.kwbooktrack.ui.common.PeriodOptions
import ru.antonkw.kwbooktrack.ui.common.periodText
import ru.antonkw.kwbooktrack.ui.common.mediaLabelRes
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.ui.common.rememberScrollbarController
import ru.antonkw.kwbooktrack.ui.common.scrollbarGestures
import ru.antonkw.kwbooktrack.ui.common.labelRes
import ru.antonkw.kwbooktrack.ui.common.seriesLabel
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onMediaClick: (MediaType, Long) -> Unit = { _, _ -> },
    onOpenStats: () -> Unit = {},
    onAddMedia: (MediaType) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.currentQuery.collectAsStateWithLifecycle()
    val isGrid by viewModel.currentLayout.collectAsStateWithLifecycle()
    val mediaType by viewModel.currentMediaType.collectAsStateWithLifecycle()
    val bookFilter by viewModel.currentBookFilter.collectAsStateWithLifecycle()
    val movieFilter by viewModel.currentMovieFilter.collectAsStateWithLifecycle()
    val period by viewModel.currentPeriod.collectAsStateWithLifecycle()
    val filter by viewModel.currentFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDatePicker by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // ISBN приводится к 13 цифрам: по сохранённому номеру в базе найдётся
        // и книга, введённая десятизначным номером.
        val isbn = Isbn.normalize(result?.contents)
        if (isbn != null) {
            viewModel.setQuery(isbn)
        }
    }

    val scanPrompt = stringResource(R.string.scan_prompt)
    // Набор вариантов фильтра «Тип» — свой для каждого типа носителя.
    val isMovies = mediaType == MediaType.MOVIES
    val filterOptions = if (isMovies) {
        MovieFilterType.entries.map { stringResource(it.labelRes) }
    } else {
        BookFilterType.entries.map { stringResource(it.labelRes) }
    }
    val selectedFilterLabel = if (isMovies) {
        stringResource(movieFilter.labelRes)
    } else {
        stringResource(bookFilter.labelRes)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    MediaTypeDropdown(
                        selected = mediaType,
                        onSelected = viewModel::setMediaType,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenCalendar) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.cd_calendar))
                    }
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.BarChart, contentDescription = stringResource(R.string.action_stats))
                    }
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.Filled.Notes, contentDescription = stringResource(R.string.cd_open_notes))
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.cd_about))
                    }
                    IconButton(onClick = viewModel::toggleLayout) {
                        if (isGrid) {
                            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = stringResource(R.string.action_list))
                        } else {
                            Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.action_grid))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddMedia(mediaType) }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(
                        if (isMovies) R.string.cd_add_movie else R.string.cd_add_book,
                    ),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear_query))
                            }
                        }
                        // Сканер ISBN — только для книг: у фильма ISBN нет.
                        if (!isMovies) {
                            IconButton(onClick = {
                                val act = context as? Activity ?: return@IconButton
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                                    setPrompt(scanPrompt)
                                    setBeepEnabled(false)
                                }
                                scanLauncher.launch(options)
                            }) {
                                Icon(
                                    Icons.Filled.QrCodeScanner,
                                    contentDescription = stringResource(R.string.cd_scan_isbn),
                                )
                            }
                        }
                    }
                },
                placeholder = {
                    Text(
                        stringResource(
                            if (isMovies) R.string.search_hint_movies else R.string.search_hint,
                        ),
                    )
                },
                singleLine = true,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = periodText(period),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_period)) },
                    trailingIcon = {
                        Icon(Icons.Filled.CalendarToday, contentDescription = stringResource(R.string.cd_choose_period))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDatePicker = true },
                    singleLine = true,
                    enabled = false,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                FilterDropdown(
                    label = stringResource(R.string.label_type),
                    selectedLabel = selectedFilterLabel,
                    options = filterOptions,
                    onSelected = { idx ->
                        if (isMovies) {
                            viewModel.setMovieFilter(MovieFilterType.entries[idx])
                        } else {
                            viewModel.setBookFilter(BookFilterType.entries[idx])
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (filter.isActive) {
                    // Зачёркнутая воронка — сброс фильтров: кнопка занимает
                    // меньше места, чем слово, и говорит о действии картинкой.
                    IconButton(onClick = viewModel::clearFilters) {
                        Icon(
                            imageVector = Icons.Filled.FilterAltOff,
                            contentDescription = stringResource(R.string.cd_reset_filters),
                        )
                    }
                }
            }

            val loadedState = state as? LibraryUiState.Loaded
            if (filter.isActive && loadedState != null) {
                Text(
                    text = stringResource(R.string.matches_count, loadedState.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val s = state) {
                LibraryUiState.Loading -> CenterBox { CircularProgressIndicator() }
                is LibraryUiState.Loaded -> {
                    if (s.items.isEmpty()) {
                        CenterBox {
                            Text(
                                text = when {
                                    !query.isBlank() -> stringResource(R.string.empty_search, query)
                                    filter.isActive -> if (isMovies) {
                                        stringResource(R.string.empty_no_matches_movies)
                                    } else {
                                        stringResource(R.string.empty_no_matches)
                                    }
                                    isMovies -> stringResource(R.string.empty_library_movies)
                                    else -> stringResource(R.string.empty_library)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        val controller = rememberScrollbarController(listState)
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Видимая полоса — 8dp в правом поле экрана, поля
                            // списка симметричны. Зона захвата (40dp) живёт
                            // в списке: полоса не залезает на карточки, но
                            // поймать её пальцем можно широко.
                            val scrollbarWidth = 8.dp
                            // Нижний отступ — под FAB, иначе последняя карточка
                            // прячется под кнопкой «Добавить».
                            val contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 16.dp,
                                bottom = 16.dp + 72.dp,
                            )
                            if (isGrid) {
                                val rows = remember(s.items) { s.items.chunked(3) }
                                LazyColumn(
                                    state = listState,
                                    contentPadding = contentPadding,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .scrollbarGestures(controller),
                                ) {
                                    itemsIndexed(rows, key = { i, row -> "${row.firstOrNull()?.id ?: 0L}_$i" }) { i, row ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(IntrinsicSize.Max),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            row.forEach { item ->
                                                when (item) {
                                                    is LibraryItem.Book -> BookCoverCard(
                                                        item = item,
                                                        onClick = { onMediaClick(MediaType.BOOKS, item.id) },
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight(),
                                                    )
                                                    is LibraryItem.Movie -> MovieCoverCard(
                                                        item = item,
                                                        onClick = { onMediaClick(MediaType.MOVIES, item.id) },
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight(),
                                                    )
                                                }
                                            }
                                            repeat(3 - row.size) {
                                                Box(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = contentPadding,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .scrollbarGestures(controller),
                                ) {
                                    items(s.items, key = { it.id }) { item ->
                                        when (item) {
                                            is LibraryItem.Book -> BookListCard(item) {
                                                onMediaClick(MediaType.BOOKS, item.id)
                                            }
                                            is LibraryItem.Movie -> MovieListCard(item) {
                                                onMediaClick(MediaType.MOVIES, item.id)
                                            }
                                        }
                                    }
                                }
                            }
                            ru.antonkw.kwbooktrack.ui.common.VerticalListScrollbar(
                                controller = controller,
                                modifier = Modifier.align(Alignment.CenterEnd),
                                width = scrollbarWidth,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val today = LocalDate.now()
        val max = YearMonth.of(today.year, today.month)
        val min = YearMonth.of(today.year - 5, 1)
        val initial = period.startMillis?.let { it.toDayDate() }
            ?.let { YearMonth.of(it.year, it.monthValue) } ?: max
        MonthYearPickerDialog(
            initial = initial,
            min = min,
            max = max,
            initialWholeYear = period.wholeYear,
            onDismiss = { showDatePicker = false },
            onConfirm = { ym ->
                viewModel.setPeriod(
                    PeriodOption.month(ym.year, ym.monthValue, PeriodOptions.monthRes(ym.monthValue)),
                )
                showDatePicker = false
            },
            onConfirmYear = { year ->
                viewModel.setPeriod(PeriodOption.year(year))
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun BookListCard(item: LibraryItem.Book, onClick: () -> Unit) {
    val book = item.book
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                path = book.coverPath,
                heightDp = 64,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    book.seriesLabel(),
                    stringResource(book.status.labelRes),
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.rating != null && book.rating > 0) {
                    Row(modifier = Modifier.padding(top = 2.dp)) {
                        repeat(book.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
                if (item.progressPercent > 0) {
                    LinearProgressIndicator(
                        progress = { item.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCoverCard(
    item: LibraryItem.Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = item.book
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            CoverImage(
                path = book.coverPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (book.rating != null && book.rating > 0) {
                        repeat(book.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(12.dp))
                    }
                }
            }
            if (item.progressPercent > 0) {
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

/**
 * Переключатель типа носителя в заголовке — «Книги ⇄ Фильмы».
 *
 * Тот же выпадающий список, что у фильтра «Тип»: список вариантов и список
 * значений — одно и то же, и порядок enum'а задаёт порядок в меню.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaTypeDropdown(
    selected: MediaType,
    onSelected: (MediaType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        // Стрелка — как у фильтра «Тип»: без неё заголовок выглядит
        // обычным текстом, и про переключение не догадаться.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.menuAnchor(),
        ) {
            Text(
                text = stringResource(selected.mediaLabelRes),
                style = MaterialTheme.typography.titleLarge,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MediaType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(type.mediaLabelRes)) },
                    onClick = { onSelected(type); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun MovieListCard(item: LibraryItem.Movie, onClick: () -> Unit) {
    val movie = item.movie
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                path = movie.coverPath,
                heightDp = 64,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movie.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = movie.director.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    movie.genre,
                    movie.durationMin?.let { formatDuration(it) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (movie.rating != null && movie.rating > 0) {
                    Row(modifier = Modifier.padding(top = 2.dp)) {
                        repeat(movie.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieCoverCard(
    item: LibraryItem.Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val movie = item.movie
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            CoverImage(
                path = movie.coverPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = movie.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = movie.director.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (movie.rating != null && movie.rating > 0) {
                        repeat(movie.rating) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
