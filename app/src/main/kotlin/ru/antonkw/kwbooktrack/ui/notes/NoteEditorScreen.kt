package ru.antonkw.kwbooktrack.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.ui.common.MarkdownHelpDialog
import ru.antonkw.kwbooktrack.ui.common.NotePhotoTile
import ru.antonkw.kwbooktrack.ui.common.PhotoGalleryDialog
import ru.antonkw.kwbooktrack.ui.common.PhotoSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadedKind by viewModel.loadedKind.collectAsStateWithLifecycle()
    // У новой заметки loadedKind == null (её kind ещё не из чего взять) —
    // берём тип из маршрута: «Написать рецензию» должно открывать
    // редактор рецензии, а не «заметку/цитату».
    val effectiveKind = loadedKind ?: viewModel.initialKind
    val isReviewFlow = effectiveKind == NoteKind.REVIEW
    val editorTitle = stringResource(
        if (isReviewFlow) R.string.note_editor_review_title else R.string.note_editor_title,
    )

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.attachPhoto(uri)
    }

    var showMarkdownHelp by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val photosFailedMessage = stringResource(R.string.note_photos_failed, state.failedPhotos)

    // Все фото на месте — уходим назад, как раньше. Часть не прикрепилась —
    // остаёмся и говорим об этом: молча закрыть экран значило бы соврать.
    LaunchedEffect(state.saved, state.failedPhotos) {
        when {
            !state.saved -> Unit
            state.failedPhotos > 0 -> snackbarHostState.showSnackbar(photosFailedMessage)
            else -> onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(editorTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.pageText,
                onValueChange = viewModel::setPage,
                label = { Text(stringResource(R.string.label_page_optional)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                label = { Text(editorTitle) },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            // Подсказка про Markdown + кнопка «?» со шпаргалкой.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.note_editor_markdown_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { showMarkdownHelp = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.action_markdown_help),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Live preview — markdown support means what the user types here is
            // rendered with headings/lists/links on the detail screen.
            if (state.body.isNotBlank()) {
                Text(
                    text = stringResource(R.string.note_editor_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    ru.antonkw.kwbooktrack.ui.common.MarkdownText(
                        markdown = state.body,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Text(stringResource(R.string.action_attach_photo))
            }

            val keptExisting = state.existingPhotos.filterNot { it.id in state.photoIdsToDelete }
            val allPhotos = (keptExisting.map { EditorPhoto.Existing(it) } + state.pendingPhotos.map { EditorPhoto.Pending(it) })
            if (allPhotos.isNotEmpty()) {
                EditorPhotoGallery(
                    photos = allPhotos,
                    onRemoveExisting = viewModel::markExistingPhotoForDeletion,
                    onRemovePending = viewModel::removePendingPhoto,
                )
            }

            if (isReviewFlow) {
                Button(
                    onClick = { viewModel.save(NoteKind.REVIEW) },
                    enabled = state.canSaveAsNote,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save_review))
                }
            } else {
                Button(
                    onClick = { viewModel.save(NoteKind.NOTE) },
                    enabled = state.canSaveAsNote,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save_note))
                }

                Button(
                    onClick = { viewModel.save(NoteKind.QUOTE) },
                    enabled = state.canSaveAsQuote,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save_quote))
                }
            }
        }
    }

    if (showMarkdownHelp) {
        MarkdownHelpDialog(onDismiss = { showMarkdownHelp = false })
    }
}

private sealed interface EditorPhoto {
    val key: String
    data class Existing(val entity: PhotoEntity) : EditorPhoto {
        override val key: String get() = "e_${entity.id}"
    }
    data class Pending(val uri: android.net.Uri) : EditorPhoto {
        override val key: String get() = "p_$uri"
    }
}

@Composable
private fun EditorPhotoGallery(
    photos: List<EditorPhoto>,
    onRemoveExisting: (Long) -> Unit,
    onRemovePending: (android.net.Uri) -> Unit,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(photos, key = { it.key }) { photo ->
            val path = when (photo) {
                is EditorPhoto.Existing -> photo.entity.path
                is EditorPhoto.Pending -> photo.uri.toString()
            }
            Box {
                NotePhotoTile(path, onClick = { viewerIndex = photos.indexOf(photo) })
                IconButton(
                    onClick = {
                        when (photo) {
                            is EditorPhoto.Existing -> onRemoveExisting(photo.entity.id)
                            is EditorPhoto.Pending -> onRemovePending(photo.uri)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(50),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
    viewerIndex?.let { idx ->
        val sources = photos.map {
            when (it) {
                is EditorPhoto.Existing -> PhotoSource(it.entity.id, it.entity.path)
                is EditorPhoto.Pending -> PhotoSource(-1L, it.uri.toString())
            }
        }
        PhotoGalleryDialog(
            photos = sources,
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
        )
    }
}
