package ru.antonkw.kwbooktrack.ui.books

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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
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
import androidx.compose.material3.DatePicker
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
import androidx.compose.material3.TextButton
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
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import ru.antonkw.kwbooktrack.ui.common.MonthYearPickerDialog
import ru.antonkw.kwbooktrack.ui.common.labelRes
import ru.antonkw.kwbooktrack.ui.common.seriesLabel
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(
    onBookClick: (Long) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onAddBook: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BookListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.currentQuery.collectAsStateWithLifecycle()
    val isGrid by viewModel.currentLayout.collectAsStateWithLifecycle()
    val filterType by viewModel.currentFilterType.collectAsStateWithLifecycle()
    val period by viewModel.currentPeriod.collectAsStateWithLifecycle()
    val filter by viewModel.currentFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDatePicker by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val isbn = result?.contents?.filter { it.isDigit() }
        if (!isbn.isNullOrBlank()) {
            viewModel.setQuery(isbn.take(13))
        }
    }

    val scanPrompt = stringResource(R.string.scan_prompt)
    val filterOptions = BookFilterType.entries.map { stringResource(it.labelRes) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_list_title)) },
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
            FloatingActionButton(onClick = onAddBook) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_book))
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
                        IconButton(onClick = {
                            val act = context as? Activity ?: return@IconButton
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                                setPrompt(scanPrompt)
                                setBeepEnabled(false)
                            }
                            scanLauncher.launch(options)
                        }) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_isbn))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
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
                    selectedLabel = stringResource(filterType.labelRes),
                    options = filterOptions,
                    onSelected = { idx -> viewModel.setFilterType(BookFilterType.entries[idx]) },
                    modifier = Modifier.weight(1f),
                )
                if (filter.isActive) {
                    TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.action_reset)) }
                }
            }

            val loadedState = state as? BookListUiState.Loaded
            if (filter.isActive && loadedState != null) {
                Text(
                    text = stringResource(R.string.matches_count, loadedState.books.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val s = state) {
                BookListUiState.Loading -> CenterBox { CircularProgressIndicator() }
                is BookListUiState.Loaded -> {
                    if (s.books.isEmpty()) {
                        CenterBox {
                            Text(
                                text = when {
                                    !query.isBlank() -> stringResource(R.string.empty_search, query)
                                    filter.isActive -> stringResource(R.string.empty_no_matches)
                                    else -> stringResource(R.string.empty_library)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(modifier = Modifier.fillMaxSize()) {
                            // padding(end = scrollbarTouchWidth) reserves the
                            // scrollbar's whole touch lane — the bar is the
                            // later sibling in this Box, so it wins hit-test
                            // there and content inside the lane would be
                            // untappable.
                            val scrollbarWidth = 8.dp
                            val scrollbarTouchWidth = 24.dp
                            if (isGrid) {
                                val rows = remember(s.books) { s.books.chunked(3) }
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(end = scrollbarTouchWidth),
                                ) {
                                    itemsIndexed(rows, key = { i, row -> "${row.firstOrNull()?.book?.id ?: 0L}_$i" }) { i, row ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(IntrinsicSize.Max),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            row.forEach { item ->
                                                BookCoverCard(
                                                    item = item,
                                                    onClick = { onBookClick(item.book.id) },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight(),
                                                )
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
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(end = scrollbarTouchWidth),
                                ) {
                                    items(s.books, key = { it.book.id }) {
                                        BookListCard(it, onClick = { onBookClick(it.book.id) })
                                    }
                                }
                            }
                            ru.antonkw.kwbooktrack.ui.common.VerticalListScrollbar(
                                listState = listState,
                                modifier = Modifier.align(Alignment.CenterEnd),
                                width = scrollbarWidth,
                                touchWidth = scrollbarTouchWidth,
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
        val initial = period.startMillis?.let { it.toLocalDate() }
            ?.let { YearMonth.of(it.year, it.monthValue) } ?: max
        MonthYearPickerDialog(
            initial = initial,
            min = min,
            max = max,
            onDismiss = { showDatePicker = false },
            onConfirm = { ym ->
                viewModel.setPeriod(
                    PeriodOption.month(ym.year, ym.monthValue, PeriodOptions.monthRes(ym.monthValue)),
                )
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun BookListCard(item: BookWithProgress, onClick: () -> Unit) {
    val book = item.book
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCoverImage(
                path = book.coverPath,
                sizeDp = 64,
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
    item: BookWithProgress,
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
            BookCoverImage(
                path = book.coverPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
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

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Разрешает `PeriodOption` (StringRes + опциональный аргумент) в локализованную строку.
 * Для месяца аргумент имеет формат `year|stringResId` — парсим и подставляем оба.
 */
@Composable
private fun periodText(option: PeriodOption): String {
    val arg = option.formatArg ?: return stringResource(option.labelRes)
    return when (option.labelRes) {
        R.string.period_year -> stringResource(option.labelRes, arg)
        R.string.period_year_month -> {
            val parts = arg.split("|", limit = 2)
            val year = parts.getOrNull(0)?.toIntOrNull()
            val monthRes = parts.getOrNull(1)?.toIntOrNull()
            if (year != null && monthRes != null) {
                stringResource(option.labelRes, year, stringResource(monthRes))
            } else {
                stringResource(option.labelRes, arg)
            }
        }
        else -> stringResource(option.labelRes, arg)
    }
}

@Composable
internal fun BookCoverImage(
    path: String?,
    sizeDp: Int? = null,
    modifier: Modifier = Modifier,
) {
    val base = when {
        sizeDp != null -> modifier.size(sizeDp.dp)
        else -> modifier
    }
    if (path.isNullOrBlank()) {
        Box(
            modifier = base.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            model = java.io.File(path),
            contentDescription = null,
            modifier = base,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    selectedLabel: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEachIndexed { idx, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelected(idx)
                        expanded = false
                    },
                )
            }
        }
    }
}
