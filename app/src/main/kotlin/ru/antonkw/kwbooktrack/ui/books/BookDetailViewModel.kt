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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.media.mediaDates
import ru.antonkw.kwbooktrack.data.progress.ReadingProgress
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
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
    /** Даты прочтения — по возрастанию. */
    val dates: List<Long> = emptyList(),
    // Сколько страниц прочитано в каждой сессии журнала (id сессии -> страницы).
    val sessionDeltas: Map<Long, Int> = emptyMap(),
    /** Теги книги — как у фильма, теги общие. */
    val tags: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val sessionRepository: SessionRepository,
    private val noteRepository: NoteRepository,
    private val photoRepository: PhotoRepository,
    private val tagRepository: TagRepository,
    private val mediaDateRepository: MediaDateRepository,
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
                bookRepository.observeById(bookId),
                sessionRepository.observeByBook(bookId),
                noteRepository.observeByMedia(bookId, MediaType.BOOKS),
                photoRepository.observeByMedia(bookId, MediaType.BOOKS),
                // Теги и даты — одним потоком: у combine пять аргументов.
                combine(
                    tagRepository.observeTagsOfMedia(bookId, MediaType.BOOKS),
                    mediaDateRepository.observeFor(bookId, MediaType.BOOKS),
                ) { tags, extraDates -> tags to extraDates },
            ) { book, sessions, notes, photos, tagsAndDates ->
                val progress = computeProgress(book, sessions)
                BookDetailUiState(
                    book,
                    sessions,
                    notes,
                    photos,
                    progress,
                    // Даты прочтения: основная плюс дополнительные.
                    mediaDates(book?.finishedAt, tagsAndDates.second.map { it.date }),
                    StatsCalculator.sessionDeltas(sessions),
                    tagsAndDates.first,
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
        // Отметить прочитанным — добавить дату, а не заменить: книга,
        // прочитанная в январе и отмеченная прочитанной в ноябре, сохраняет
        // обе даты. Основная — самая ранняя.
        val extra = mediaDateRepository.getFor(bookId, MediaType.BOOKS)
        // Дата — полночь по местному времени (как в остальных колонках с
        // датами), иначе «два прочтения» в один день не отличались бы от
        // одного.
        val today = LocalDate.now().toEpochMs()
        // Прежние даты перечитывания не теряются: «отметить прочитанным»
        // добавляет дату, а не заменяет список.
        val dates = (listOfNotNull(book.finishedAt) + extra + today).distinct().sorted()
        bookRepository.update(
            book.copy(
                status = ru.antonkw.kwbooktrack.data.db.entity.BookStatus.FINISHED,
                finishedAt = dates.first(),
                rating = rating?.coerceIn(1, 5),
                updatedAt = now,
            ),
        )
        mediaDateRepository.replaceFor(bookId, MediaType.BOOKS, dates.drop(1))
    }

    fun deleteBook() {
        viewModelScope.launch {
            // Файлы фото удаляет репозиторий: он читает пути до удаления
            // строк и снимает их с диска. Здесь второй раз удалять нечего —
            // два места с одной логикой расходятся при правке.
            bookRepository.deleteById(bookId)
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
