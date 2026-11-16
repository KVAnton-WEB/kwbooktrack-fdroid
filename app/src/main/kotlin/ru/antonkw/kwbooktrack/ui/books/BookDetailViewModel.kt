package ru.antonkw.kwbooktrack.ui.books

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.progress.ReadingProgress
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import javax.inject.Inject

data class BookProgress(
    val pagesRead: Int,
    val totalPages: Int?,
    val percent: Int,
    val pagesRemaining: Int?,
)

data class BookDetailUiState(
    val book: BookEntity?,
    val sessions: List<ReadingSessionEntity>,
    val notes: List<NoteEntity>,
    val photos: List<PhotoEntity>,
    val progress: BookProgress,
    // Сколько страниц прочитано в каждой сессии журнала (id сессии -> страницы).
    val sessionDeltas: Map<Long, Int> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val sessionRepository: SessionRepository,
    private val noteRepository: NoteRepository,
    private val photoRepository: PhotoRepository,
    private val photoStorage: PhotoStorage,
) : ViewModel() {

    private val bookId: Long = savedStateHandle.get<Long>(KEY_BOOK_ID) ?: -1L
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val uiState: StateFlow<BookDetailUiState> =
        if (bookId <= 0L) {
            flowOf(BookDetailUiState(null, emptyList(), emptyList(), emptyList(), BookProgress(0, null, 0, null)))
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyState())
        } else {
            combine(
                bookRepository.observeAll().flatMapLatest { all ->
                    kotlinx.coroutines.flow.flowOf(all.firstOrNull { it.id == bookId })
                },
                sessionRepository.observeByBook(bookId),
                noteRepository.observeByMedia(bookId, MediaType.BOOKS),
                photoRepository.observeByMedia(bookId, MediaType.BOOKS),
            ) { book, sessions, notes, photos ->
                val progress = computeProgress(book, sessions)
                BookDetailUiState(
                    book,
                    sessions,
                    notes,
                    photos,
                    progress,
                    StatsCalculator.sessionDeltas(sessions),
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyState())
        }

    suspend fun setCurrentPage(page: Int) {
        val now = System.currentTimeMillis()
        val book = bookRepository.findById(bookId) ?: return
        sessionRepository.insert(
            ReadingSessionEntity(
                bookId = bookId,
                startedAt = now,
                endPage = page,
                durationSec = null,
            ),
        )
        if (book.status != ru.antonkw.kwbooktrack.data.db.entity.BookStatus.FINISHED && page > 0) {
            bookRepository.update(
                book.copy(
                    status = ru.antonkw.kwbooktrack.data.db.entity.BookStatus.READING,
                    startedAt = book.startedAt ?: now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun markFinished(rating: Int?) {
        val now = System.currentTimeMillis()
        val book = bookRepository.findById(bookId) ?: return
        bookRepository.update(
            book.copy(
                status = ru.antonkw.kwbooktrack.data.db.entity.BookStatus.FINISHED,
                finishedAt = now,
                rating = rating?.coerceIn(1, 5),
                updatedAt = now,
            ),
        )
    }

    fun deleteBook() {
        viewModelScope.launch {
            // Snapshot photo paths BEFORE the cascade — the DAO will also delete
            // the rows in BookRepository.deleteById (issue #18) and the storage
            // layer unlinks them. Going through photoStorage keeps the cleanup
            // in one place and avoids unlinking a file the user might have
            // reattached to a different book.
            val photoPaths = photoRepository.observeByMedia(bookId, MediaType.BOOKS).first().map { it.path }
            // FK CASCADE on sessions, notes, and photos removes DB records; the
            // repository also runs photoStorage.deletePaths for us.
            bookRepository.deleteById(bookId)
            photoStorage.deletePaths(photoPaths)
            _deleted.value = true
        }
    }

    private fun computeProgress(book: BookEntity?, sessions: List<ReadingSessionEntity>): BookProgress {
        if (book == null) return BookProgress(0, null, 0, null)
        val pagesRead = ReadingProgress.pagesRead(book, sessions)
        val total = book.pageCount
        val percent = if (total != null && total > 0) (pagesRead.coerceAtMost(total) * 100 / total) else 0
        val remaining = total?.let { (it - pagesRead).coerceAtLeast(0) }
        return BookProgress(pagesRead, total, percent, remaining)
    }

    private fun emptyState() = BookDetailUiState(null, emptyList(), emptyList(), emptyList(), BookProgress(0, null, 0, null))

    companion object {
        const val KEY_BOOK_ID = "bookId"
    }
}
