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

    private val bookId: Long = savedStateHandle.get<Long>(KEY_BOOK_ID) ?: -1L
    private val noteId: Long = savedStateHandle.get<Long>(KEY_NOTE_ID) ?: 0L

    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    init {
        if (noteId > 0L) {
            viewModelScope.launch {
                val note = noteRepository.findById(noteId)
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

    fun save(kind: NoteKind = NoteKind.NOTE) {
        val s = _state.value
        if (kind == NoteKind.QUOTE && s.body.isBlank()) return
        if (!s.canSaveAsNote) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = NoteEntity(
                id = if (noteId > 0L) noteId else 0L,
                bookId = bookId,
                page = s.pageText.toIntOrNull(),
                bodyMd = s.body,
                kind = kind,
                createdAt = if (noteId > 0L) {
                    noteRepository.findById(noteId)?.createdAt ?: now
                } else now,
                updatedAt = now,
            )
            val targetId = noteRepository.insert(entity)
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
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        try {
            val target = File(photosDir, "note_${targetNoteId}_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return
            photoRepository.insert(
                PhotoEntity(
                    bookId = bookId,
                    noteId = targetNoteId,
                    path = target.absolutePath,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_NOTE_ID = "noteId"
    }
}
