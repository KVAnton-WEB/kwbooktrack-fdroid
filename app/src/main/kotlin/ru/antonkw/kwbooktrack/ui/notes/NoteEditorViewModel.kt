package ru.antonkw.kwbooktrack.ui.notes

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import java.io.File
import javax.inject.Inject

data class NoteEditorState(
    val pageText: String = "",
    val body: String = "",
    val existingPhotos: List<PhotoEntity> = emptyList(),
    val pendingPhotos: List<Uri> = emptyList(),
    val photoIdsToDelete: Set<Long> = emptySet(),
    val loaded: Boolean = false,
    val saved: Boolean = false,
) {
    val totalPhotos: Int get() = (existingPhotos.size - photoIdsToDelete.size) + pendingPhotos.size

    val canSaveAsNote: Boolean get() = body.isNotBlank() || totalPhotos > 0
    val canSaveAsQuote: Boolean get() = body.isNotBlank()
}

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val noteRepository: NoteRepository,
    private val photoRepository: PhotoRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val noteId: Long = savedStateHandle.get<Long>(KEY_NOTE_ID) ?: 0L
    // Владелец заметки — книга или фильм. У новой заметки он приезжает в
    // маршруте; у правки маршрут несёт только id заметки, и владельца мы
    // берём из самой заметки — иначе она отвязалась бы от своей книги.
    private var mediaId: Long = savedStateHandle.get<Long>(KEY_MEDIA_ID) ?: -1L
    private var mediaType: MediaType = savedStateHandle.get<String>(KEY_MEDIA_TYPE)
        ?.let { runCatching { MediaType.valueOf(it) }.getOrNull() }
        ?: MediaType.BOOKS

    // Pre-selected note kind from the nav route (e.g. REVIEW for the
    // "Write review" entry point). Edits always keep the existing kind —
    // when noteId > 0 the loaded value below overrides this.
    val initialKind: NoteKind = savedStateHandle.get<String>(KEY_KIND)
        ?.let { runCatching { NoteKind.valueOf(it) }.getOrNull() } ?: NoteKind.NOTE

    // Holds the kind of an existing note being edited. Null until the
    // note has been loaded; null for new notes (use initialKind instead).
    private val _loadedKind = MutableStateFlow<NoteKind?>(null)
    val loadedKind: StateFlow<NoteKind?> = _loadedKind.asStateFlow()

    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    init {
        if (noteId > 0L) {
            viewModelScope.launch {
                val note = noteRepository.findById(noteId)
                if (note != null) {
                    mediaId = note.mediaId
                    mediaType = note.mediaType
                    _loadedKind.value = note.kind
                }
                val photos = photoRepository.observeByNote(noteId).first()
                _state.value = _state.value.copy(
                    pageText = note?.page?.toString() ?: "",
                    body = note?.bodyMd.orEmpty(),
                    existingPhotos = photos,
                    loaded = true,
                )
            }
        } else {
            _state.value = _state.value.copy(loaded = true)
        }
    }

    fun setBody(text: String) {
        _state.value = _state.value.copy(body = text)
    }

    fun setPage(text: String) {
        _state.value = _state.value.copy(pageText = text.filter { it.isDigit() })
    }

    fun attachPhoto(uri: Uri) {
        _state.value = _state.value.copy(
            pendingPhotos = _state.value.pendingPhotos + uri,
        )
    }

    fun removePendingPhoto(uri: Uri) {
        _state.value = _state.value.copy(
            pendingPhotos = _state.value.pendingPhotos.filterNot { it == uri },
        )
    }

    fun markExistingPhotoForDeletion(id: Long) {
        _state.value = _state.value.copy(
            photoIdsToDelete = _state.value.photoIdsToDelete + id,
        )
    }

    fun unmarkExistingPhotoForDeletion(id: Long) {
        _state.value = _state.value.copy(
            photoIdsToDelete = _state.value.photoIdsToDelete - id,
        )
    }

    fun save(kind: NoteKind? = null) {
        val effectiveKind = kind ?: _loadedKind.value ?: initialKind
        val s = _state.value
        if (effectiveKind == NoteKind.QUOTE && s.body.isBlank()) return
        if (!s.canSaveAsNote) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = NoteEntity(
                id = if (noteId > 0L) noteId else 0L,
                mediaId = mediaId,
                mediaType = mediaType,
                page = s.pageText.toIntOrNull(),
                bodyMd = s.body,
                kind = effectiveKind,
                createdAt = if (noteId > 0L) {
                    noteRepository.findById(noteId)?.createdAt ?: now
                } else now,
                updatedAt = now,
            )
            // Правка существующей заметки — через update, а не insert:
            // insert помечен REPLACE, а это в SQLite удаление старой строки
            // плюс вставка новой. Удаление заметки срабатывает внешний ключ
            // photos.note_id (ON DELETE SET NULL) — и привязка всех её фото
            // обнулялась бы. Если заметки уже нет — вставляем заново.
            val targetId = if (noteId > 0L) {
                if (noteRepository.update(entity) == 0) noteRepository.insert(entity) else noteId
            } else {
                noteRepository.insert(entity)
            }
            s.photoIdsToDelete.forEach { id ->
                photoRepository.observeByNote(targetId).first()
                    .firstOrNull { it.id == id }
                    ?.let { photoRepository.delete(it) }
            }
            s.pendingPhotos.forEach { uri -> copyToFilesAndInsert(uri, targetId) }
            _state.value = s.copy(
                saved = true,
                pendingPhotos = emptyList(),
                photoIdsToDelete = emptySet(),
            )
        }
    }

    private suspend fun copyToFilesAndInsert(uri: Uri, targetNoteId: Long) {
        try {
            // Файл — через PhotoStorage: там имя с UUID. Собственное имя из
            // миллисекунд давало бы двум фото, прикреплённым в одну
            // миллисекунду, одно имя — второе перезаписало бы первое.
            val path = context.contentResolver.openInputStream(uri)?.use { input ->
                photoRepository.saveForNote(targetNoteId, input, "jpg")
            } ?: return
            photoRepository.insert(
                PhotoEntity(
                    mediaId = mediaId,
                    mediaType = mediaType,
                    noteId = targetNoteId,
                    path = path,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        const val KEY_MEDIA_ID = "mediaId"
        const val KEY_MEDIA_TYPE = "mediaType"
        const val KEY_NOTE_ID = "noteId"
        const val KEY_KIND = "kind"
    }
}
