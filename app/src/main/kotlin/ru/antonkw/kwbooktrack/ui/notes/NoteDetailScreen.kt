package ru.antonkw.kwbooktrack.ui.notes

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.time.toLocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.ui.common.PhotoGalleryDialog
import ru.antonkw.kwbooktrack.ui.common.PhotoSource

sealed interface NoteDetailUiState {
    data object Loading : NoteDetailUiState
    data class Loaded(
        val note: NoteEntity,
        val mediaTitle: String,
        val mediaSubtitle: String,
        val coverPath: String?,
        val photos: List<PhotoEntity>,
    ) : NoteDetailUiState
    data object NotFound : NoteDetailUiState
}

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteRepository: NoteRepository,
    private val bookRepository: BookRepository,
    private val movieRepository: MovieRepository,
    private val photoRepository: PhotoRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<NoteDetailUiState>(NoteDetailUiState.Loading)
    val state: StateFlow<NoteDetailUiState> = _state.asStateFlow()
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    // Holds the in-flight combine().collect so we can cancel it before launching a new
    // load() — otherwise rapid navigation between notes leaks subscriptions and the
    // latest emission wins by accident instead of deterministically.
    private var loadJob: Job? = null

    fun load(noteId: Long) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val note = noteRepository.findById(noteId) ?: run {
                _state.value = NoteDetailUiState.NotFound
                return@launch
            }
            // Подстановка «владелец удалён» — по типу носителя: у фильма
            // это не «книга удалена».
            val deletedFallback = context.getString(
                if (note.mediaType == ru.antonkw.kwbooktrack.data.db.entity.MediaType.MOVIES) {
                    R.string.about_movie_deleted_fallback
                } else {
                    R.string.about_book_deleted_fallback
                },
            )
            combine(
                bookRepository.observeAll(),
                movieRepository.observeAll(),
                photoRepository.observeByNote(noteId),
            ) { books, movies, photos ->
                // Владелец — книга или фильм: id у них считаются независимо,
                // поэтому ищем по паре (id, тип).
                val title: String
                val subtitle: String
                val cover: String?
                if (note.mediaType == MediaType.MOVIES) {
                    val movie = movies.firstOrNull { it.id == note.mediaId }
                    title = movie?.title ?: deletedFallback
                    subtitle = movie?.director.orEmpty()
                    cover = movie?.coverPath
                } else {
                    val book = books.firstOrNull { it.id == note.mediaId }
                    title = book?.title ?: deletedFallback
                    subtitle = book?.author.orEmpty()
                    cover = book?.coverPath
                }
                NoteDetailUiState.Loaded(
                    note = note,
                    mediaTitle = title,
                    mediaSubtitle = subtitle,
                    coverPath = cover,
                    photos = photos,
                )
            }.collect { _state.value = it }
        }
    }

    fun delete() {
        val current = (_state.value as? NoteDetailUiState.Loaded) ?: return
        viewModelScope.launch {
            noteRepository.delete(current.note)
            current.photos.forEach { photoRepository.delete(it) }
            _deleted.value = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: Long,
    onBack: () -> Unit,
    onOpenMedia: (MediaType, Long) -> Unit,
    onEditNote: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.note_copied)

    LaunchedEffect(noteId) { viewModel.load(noteId) }
    LaunchedEffect(deleted) { if (deleted) onBack() }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.dialog_delete_note_title)) },
            text = { Text(stringResource(R.string.dialog_delete_note_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val s = state) {
                            is NoteDetailUiState.Loaded -> when (s.note.kind) {
                                NoteKind.QUOTE -> stringResource(R.string.note_kind_quote)
                                NoteKind.REVIEW -> stringResource(R.string.note_kind_review)
                                NoteKind.NOTE -> stringResource(R.string.note_kind_note)
                            }
                            else -> stringResource(R.string.note_kind_unknown)
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val loaded = state as? NoteDetailUiState.Loaded
                    if (loaded != null) {
                        val pageLine = loaded.note.page?.let { stringResource(R.string.note_page_label, it) }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(loaded.note.bodyMd))
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, loaded.mediaTitle)
                                putExtra(Intent.EXTRA_TEXT, buildShareText(loaded, pageLine))
                            }
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share)))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(onClick = { onEditNote(loaded.note.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                NoteDetailUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                NoteDetailUiState.NotFound -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.note_not_found)) }
                is NoteDetailUiState.Loaded -> NoteDetailContent(s, onOpenMedia)
            }
        }
    }
}

@Composable
private fun NoteDetailContent(state: NoteDetailUiState.Loaded, onOpenMedia: (MediaType, Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenMedia(state.note.mediaType, state.note.mediaId) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // AsyncImage falls back to the error branch when the file is missing;
            // no File.exists() on the UI thread.
            if (!state.coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = java.io.File(state.coverPath),
                    contentDescription = state.mediaTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 96.dp, height = 144.dp),
                )
            } else {
                Icon(
                    imageVector = when (state.note.kind) {
                        NoteKind.QUOTE -> Icons.Filled.FormatQuote
                        NoteKind.REVIEW -> Icons.Filled.RateReview
                        NoteKind.NOTE -> Icons.Filled.Star
                    },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = state.mediaTitle,
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.mediaSubtitle.isNotBlank()) {
                Text(
                    text = state.mediaSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.photos.isNotEmpty()) {
            var viewerIndex by remember { mutableStateOf<Int?>(null) }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.photos, key = { it.id }) { photo ->
                    val idx = state.photos.indexOf(photo)
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { viewerIndex = idx },
                    ) {
                        AsyncImage(
                            model = java.io.File(photo.path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            viewerIndex?.let { idx ->
                PhotoGalleryDialog(
                    photos = state.photos.map { PhotoSource(it.id, it.path) },
                    initialIndex = idx,
                    onDismiss = { viewerIndex = null },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.note.page?.let {
                Text(stringResource(R.string.note_page_label, it), style = MaterialTheme.typography.labelLarge)
            }
            // Rendered Markdown body — replaces the previous plain Text so
            // headings/lists/links show up properly. Editor screen still shows
            // the raw markdown source.
            ru.antonkw.kwbooktrack.ui.common.MarkdownText(
                markdown = state.note.bodyMd,
                textColor = MaterialTheme.colorScheme.onSurface,
                textStyle = if (state.note.kind == NoteKind.QUOTE)
                    MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                else MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.note_created_label,
                    remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm") }
                        .format(state.note.createdAt.toLocalDateTime()),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun buildShareText(state: NoteDetailUiState.Loaded, pageLine: String?): String {
    val header = buildString {
        append(state.mediaTitle)
        if (state.mediaSubtitle.isNotBlank()) {
            append(" — ")
            append(state.mediaSubtitle)
        }
    }
    val body = state.note.bodyMd.trim()
    return if (pageLine != null) {
        "$header\n\n$pageLine\n\n$body"
    } else {
        "$header\n\n$body"
    }
}
