package ru.antonkw.kwbooktrack.ui.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.ui.common.PhotoGalleryDialog
import ru.antonkw.kwbooktrack.ui.common.PhotoSource
import ru.antonkw.kwbooktrack.ui.common.labelRes
import ru.antonkw.kwbooktrack.ui.common.seriesLabel
import java.time.Instant
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import ru.antonkw.kwbooktrack.data.time.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onAddNote: (Long) -> Unit = {},
    onAddReview: (Long) -> Unit = {},
    onEditBook: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onDeleted: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pageInput by remember { mutableStateOf("") }
    var notesQuery by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    var tempRating by remember { mutableStateOf(0) }
    val book = state.book

    androidx.compose.runtime.LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dialog_delete_book_title)) },
            text = { Text(stringResource(R.string.dialog_delete_book_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteBook()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showInfoDialog && book != null) {
        ru.antonkw.kwbooktrack.ui.common.BookInfoDialog(
            book = book,
            onDismiss = { showInfoDialog = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: stringResource(R.string.book_detail_fallback_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    book?.let { b ->
                        IconButton(onClick = { onEditBook(b.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            book?.let { b ->
                FloatingActionButton(onClick = { onAddNote(b.id) }) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.action_add_note))
                }
            }
        },
    ) { padding ->
        if (book == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        // Полноэкранный просмотр обложки — как фото в заметках.
        val coverPath = book.coverPath
        if (showCoverViewer && !coverPath.isNullOrBlank()) {
            PhotoGalleryDialog(
                // id здесь — id фото; у обложки книги своего id нет, поэтому -1.
                photos = listOf(PhotoSource(-1L, coverPath)),
                initialIndex = 0,
                onDismiss = { showCoverViewer = false },
            )
        }

        val detailListState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Полоса прокрутки живёт в правом поле экрана: поле листа равно
        // ширине полосы, поэтому она не заезжает на контент и поля списка
        // остаются симметричными слева и справа.
        val scrollbarTouchWidth = 16.dp
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            item {
                Card {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BookCoverImage(
                                path = coverPath,
                                sizeDp = 96,
                                modifier = Modifier.padding(end = 4.dp),
                                contentDescription = stringResource(R.string.label_cover),
                                onClick = if (!coverPath.isNullOrBlank()) {
                                    { showCoverViewer = true }
                                } else null,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.headlineSmall)
                                Text(book.author, style = MaterialTheme.typography.bodyMedium)
                                val meta = listOfNotNull(
                                    book.year?.toString(),
                                    book.publisher,
                                    book.genre,
                                    stringResource(book.status.labelRes),
                                    book.rating?.let { "★".repeat(it) },
                                ).joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(meta, style = MaterialTheme.typography.bodySmall)
                                }
                                val purchaseInfo = listOfNotNull(
                                    book.purchaseDate?.let { formatDate(it) }?.let { stringResource(R.string.label_purchased_on, it) },
                                    book.purchasePrice?.let { stringResource(R.string.price_value, it) },
                                )
                                if (purchaseInfo.isNotEmpty()) {
                                    Text(
                                        text = purchaseInfo.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                book.finishedAt?.let { ts ->
                                    Text(
                                        text = stringResource(R.string.label_finished_on, formatDate(ts)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // v5: surface the new Tellico fields the user already cares about
            // (binding, subtitle, series). All other new fields live behind
            // the "Show all details" dialog — they clutter the main view.
            val v5Line = listOfNotNull(
                book.subtitle,
                book.seriesLabel(),
                book.binding,
            )
            if (v5Line.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = v5Line.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { showInfoDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.book_info_show_all))
                            }
                        }
                    }
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.book_info_show_all))
                    }
                }
            }

            item {
                Card {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.label_progress), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.progress.totalPages?.let {
                                stringResource(R.string.progress_format, state.progress.pagesRead, it, state.progress.percent)
                            } ?: stringResource(R.string.progress_no_page_count),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.progress.totalPages != null) {
                            LinearProgressIndicator(
                                progress = { (state.progress.percent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                        if (state.progress.pagesRemaining != null && state.progress.pagesRemaining!! > 0) {
                            Text(
                                text = stringResource(R.string.pages_remaining, state.progress.pagesRemaining ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val total = book.pageCount
                        val p = pageInput.toIntOrNull()
                        val pageInRange = p != null && p in 1..(total ?: Int.MAX_VALUE)
                        val pageError = total != null && p != null && p > total
                        OutlinedTextField(
                            value = pageInput,
                            onValueChange = { pageInput = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.label_current_page)) },
                            singleLine = true,
                            isError = pageError,
                            supportingText = if (pageError) {
                                { Text(stringResource(R.string.error_page_exceeds_total, total!!)) }
                            } else null,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val v = p ?: return@IconButton
                                        if (!pageInRange) return@IconButton
                                        scope.launch {
                                            viewModel.setCurrentPage(v)
                                            pageInput = ""
                                        }
                                    },
                                    enabled = pageInRange,
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = stringResource(R.string.cd_save_current_page),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                        if (book.status != BookStatus.FINISHED) {
                            OutlinedButton(
                                onClick = {
                                    tempRating = book.rating ?: 0
                                    showFinishDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) { Text(stringResource(R.string.action_i_finished)) }
                            if (showFinishDialog) {
                                AlertDialog(
                                    onDismissRequest = { showFinishDialog = false },
                                    title = { Text(stringResource(R.string.dialog_rate_title)) },
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            (1..5).forEach { star ->
                                                IconButton(onClick = {
                                                    tempRating = if (tempRating == star) 0 else star
                                                }) {
                                                    if (tempRating >= star) {
                                                        Icon(
                                                            Icons.Filled.Star,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.tertiary,
                                                        )
                                                    } else {
                                                        Icon(Icons.Outlined.Star, contentDescription = null)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showFinishDialog = false
                                            scope.launch {
                                                viewModel.markFinished(tempRating.takeIf { it > 0 })
                                            }
                                        }) { Text(stringResource(R.string.action_save)) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showFinishDialog = false }) {
                                            Text(stringResource(R.string.action_cancel))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Рецензия (отдельная карточка)
            val review = state.notes.firstOrNull { it.kind == NoteKind.REVIEW }
            if (review != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNote(review.id) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.label_section_review),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            // Markdown body — opens the full note on tap.
                            ru.antonkw.kwbooktrack.ui.common.MarkdownText(
                                markdown = review.bodyMd,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            } else {
                // No review yet — surface the entry point so users don't have
                // to dig into the add-note FAB just to write one.
                item {
                    OutlinedButton(
                        onClick = { onAddReview(book.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.RateReview, contentDescription = null)
                        Text(
                            text = "  " + stringResource(R.string.action_write_review),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            // Заметки и цитаты
            val notesQ = notesQuery.trim().lowercase()
            val photoByNoteId = state.photos.groupBy({ it.noteId }, { it.path }).mapValues { it.value.firstOrNull() }
            val quoteNotes = state.notes.filter {
                it.kind == NoteKind.QUOTE && (notesQ.isEmpty() || it.bodyMd.lowercase().contains(notesQ))
            }
            val regularNotes = state.notes.filter {
                it.kind == NoteKind.NOTE && (notesQ.isEmpty() || it.bodyMd.lowercase().contains(notesQ))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            stringResource(R.string.notes_list_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = notesQuery,
                            onValueChange = { notesQuery = it },
                            placeholder = { Text(stringResource(R.string.notes_search_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        if (quoteNotes.isNotEmpty() || regularNotes.isNotEmpty()) {
                            if (regularNotes.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.notes_filter_notes) + " (${regularNotes.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            regularNotes.forEach { note ->
                                NoteRow(note, photoByNoteId[note.id], onOpenNote)
                            }
                            if (quoteNotes.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.notes_filter_quotes) + " (${quoteNotes.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            quoteNotes.forEach { quote ->
                                QuoteRow(quote, photoByNoteId[quote.id], onOpenNote)
                            }
                        } else if (notesQ.isNotEmpty()) {
                            Text(
                                stringResource(R.string.notes_no_results, notesQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.label_sessions_count, state.sessions.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.sessions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty_sessions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(session, state.sessionDeltas[session.id])
                }
            }

            // Padding снизу чтобы FAB не перекрывал последний элемент
            item { Spacer(modifier = Modifier.height(72.dp)) }
            }
            ru.antonkw.kwbooktrack.ui.common.VerticalListScrollbar(
                listState = detailListState,
                modifier = Modifier.align(Alignment.CenterEnd),
                width = 8.dp,
                touchWidth = scrollbarTouchWidth,
            )
        }
    }
}

@Composable
private fun NoteRow(note: NoteEntity, photoPath: String?, onOpen: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(note.id) }
            .padding(vertical = 8.dp),
    ) {
        if (photoPath != null) {
            AsyncImage(
                model = java.io.File(photoPath),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(vertical = 4.dp),
            )
        }
        note.page?.let {
            Text(stringResource(R.string.session_end_page, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = note.bodyMd,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = formatDate(note.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun QuoteRow(quote: NoteEntity, photoPath: String?, onOpen: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(quote.id) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.FormatQuote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            if (photoPath != null) {
                AsyncImage(
                    model = java.io.File(photoPath),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(vertical = 4.dp),
                )
            }
            Text(
                text = quote.bodyMd,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            quote.page?.let {
                Text(stringResource(R.string.session_end_page, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SessionRow(session: ReadingSessionEntity, delta: Int?) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = SESSION_FMT.format(session.startedAt.toLocalDateTime()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Сколько страниц прочитано с прошлой записи журнала.
                // Ноль не показываем: это либо та же страница, что и раньше,
                // либо ничего не прочитано — в журнале это шум.
                if (delta != null && delta != 0) {
                    Text(
                        text = stringResource(R.string.session_page_delta, delta),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = session.endPage?.let { stringResource(R.string.session_end_page, it) } ?: stringResource(R.string.session_no_page),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// File-level DateTimeFormatters: thread-safe and created exactly once per
// process instead of per recomposition (DateTimeFormatter.ofPattern is not
// cheap). DateTimeFormatter itself is immutable and documented as thread-safe.
private val SESSION_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale("ru"))
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale("ru"))

private fun formatDate(epochMs: Long): String =
    DATE_FMT.format(epochMs.toLocalDate())
