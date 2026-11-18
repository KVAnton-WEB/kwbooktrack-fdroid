package ru.antonkw.kwbooktrack.ui.movies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.data.time.toDayMs
import ru.antonkw.kwbooktrack.data.time.toLocalDay
import ru.antonkw.kwbooktrack.ui.common.CoverImage
import ru.antonkw.kwbooktrack.ui.common.ExtraFieldsSection
import ru.antonkw.kwbooktrack.ui.common.formatDuration
import ru.antonkw.kwbooktrack.ui.common.MarkdownText
import ru.antonkw.kwbooktrack.ui.common.UrlText
import ru.antonkw.kwbooktrack.ui.common.asUrl
import ru.antonkw.kwbooktrack.ui.common.MediaTagsSection
import ru.antonkw.kwbooktrack.ui.common.PhotoGalleryDialog
import ru.antonkw.kwbooktrack.ui.common.PhotoSource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Карточка фильма: обложка, поля, рецензия, заметки, цитаты, теги и фото.
 *
 * Трекера страниц нет — у фильма нет страниц.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    onBack: () -> Unit = {},
    onEditMovie: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onAddNote: (Long) -> Unit = {},
    onAddReview: (Long) -> Unit = {},
    onDeleted: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MovieDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    var galleryIndex by remember { mutableStateOf(-1) }
    var showWatchPicker by remember { mutableStateOf(false) }

    // Навигацию зовём из эффекта, а не из тела композиции: popBackStack во
    // время композиции оставлял NavHost с пустым экраном — чёрный кадр без
    // падения. У книги это уже было сделано через LaunchedEffect.
    LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }
    if (deleted) return

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.movie_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    val movieId = state.movie?.id
                    if (movieId != null) {
                        IconButton(onClick = { onEditMovie(movieId) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            state.movie?.let { m ->
                FloatingActionButton(onClick = { onAddNote(m.id) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = stringResource(R.string.action_add_note),
                    )
                }
            }
        },
    ) { padding ->
        val movie = state.movie
        if (movie == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.movie_not_found), style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        val review = state.notes.firstOrNull { it.kind == NoteKind.REVIEW }
        val otherNotes = state.notes.filter { it.kind != NoteKind.REVIEW }

        // Полноэкранный просмотр обложки — как в детали книги.
        val coverPath = movie.coverPath
        if (showCoverViewer && !coverPath.isNullOrBlank()) {
            PhotoGalleryDialog(
                photos = listOf(PhotoSource(-1L, coverPath)),
                initialIndex = 0,
                onDismiss = { showCoverViewer = false },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CoverImage(
                    path = movie.coverPath,
                    heightDp = 110,
                    modifier = Modifier.padding(end = 12.dp),
                    contentDescription = stringResource(R.string.label_cover),
                    onClick = if (!movie.coverPath.isNullOrBlank()) {
                        { showCoverViewer = true }
                    } else {
                        null
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    movie.originalTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (movie.rating != null && movie.rating > 0) {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            repeat(movie.rating) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            MovieFieldRow(R.string.movie_field_year, movie.year?.toString())
            MovieFieldRow(
                R.string.movie_field_duration,
                movie.durationMin?.let { formatDuration(it) },
            )
            MovieFieldRow(R.string.movie_field_genre, movie.genre)
            MovieFieldRow(R.string.movie_field_nationality, movie.nationality)
            MovieFieldRow(R.string.movie_field_director, movie.director)
            MovieFieldRow(R.string.movie_field_cast, movie.cast?.replace("\n", " · "))
            MovieFieldRows(
                R.string.movie_field_watched_at,
                // Все даты просмотра — по возрастанию, по одной на строку.
                state.dates.map { it.toDayDate().toString() },
            )
            // Отметка просмотра — здесь же, без входа в редактор: дата по
            // умолчанию сегодняшняя, повторный просмотр добавляет ещё одну.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showWatchPicker = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text(
                        text = "  " + stringResource(
                            if (state.dates.isEmpty()) R.string.action_mark_watched else R.string.action_add_watching_date,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            MovieFieldRow(R.string.movie_field_url, movie.url)

            ExtraFieldsSection(movie.extraFields)

            if (!movie.plot.isNullOrBlank()) {
                Text(stringResource(R.string.movie_field_plot), style = MaterialTheme.typography.titleMedium)
                MarkdownText(markdown = movie.plot)
            }

            if (review != null) {
                Text(stringResource(R.string.movie_field_review), style = MaterialTheme.typography.titleMedium)
                MarkdownText(markdown = review.bodyMd)
            } else {
                // Рецензии ещё нет — показываем входную точку, чтобы не искать
                // её в кнопке добавления заметки.
                OutlinedButton(
                    onClick = { onAddReview(movie.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.RateReview, contentDescription = null)
                    Text(
                        text = "  " + stringResource(R.string.action_write_review),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (!movie.comments.isNullOrBlank()) {
                Text(stringResource(R.string.movie_field_comments), style = MaterialTheme.typography.titleMedium)
                MarkdownText(markdown = movie.comments)
            }

            MediaTagsSection(
                tags = state.tags,
                suggestions = state.allTags,
                onAddTag = viewModel::addTag,
                onRemoveTag = viewModel::removeTag,
            )

            if (otherNotes.isNotEmpty()) {
                Text(stringResource(R.string.notes_section), style = MaterialTheme.typography.titleMedium)
                otherNotes.forEach { note ->
                    NoteRow(note = note, onOpen = { onOpenNote(note.id) })
                }
            }

            if (state.photos.isNotEmpty()) {
                Text(stringResource(R.string.photos_section), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.photos.forEachIndexed { index, photo ->
                        AsyncImage(
                            model = File(photo.path),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .clickable { galleryIndex = index },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    if (galleryIndex >= 0) {
        PhotoGalleryDialog(
            photos = state.photos.map { PhotoSource(it.id, it.path) },
            initialIndex = galleryIndex,
            onDismiss = { galleryIndex = -1 },
        )
    }

    // Дата просмотра: сегодняшняя по умолчанию — чаще всего отмечают «сейчас».
    if (showWatchPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showWatchPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.addWatchDate(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toDayMs(),
                        )
                    }
                    showWatchPicker = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showWatchPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.movie_delete_title)) },
            text = { Text(stringResource(R.string.movie_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteMovie()
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Поле со списком значений — по одному на строку (даты просмотра). */
@Composable
private fun MovieFieldRows(labelRes: Int, values: List<String>) {
    if (values.isEmpty()) return
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        values.forEach {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Строка «подпись — значение». Пустые значения не показываются. */
@Composable
private fun MovieFieldRow(labelRes: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Значение-ссылка (кинопоиск) кликабельно — открывает браузер.
        val url = asUrl(value)
        if (url != null) {
            UrlText(url)
        } else {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Заметка или цитата — тап открывает её. */
@Composable
private fun NoteRow(note: NoteEntity, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = note.bodyMd,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = note.createdAt.toLocalDay().toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
