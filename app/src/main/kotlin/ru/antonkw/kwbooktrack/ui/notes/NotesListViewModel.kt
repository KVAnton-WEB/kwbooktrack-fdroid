package ru.antonkw.kwbooktrack.ui.notes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import javax.inject.Inject

data class NoteListItem(
    val note: NoteEntity,
    /** Название владельца заметки — книги или фильма. */
    val mediaTitle: String?,
    /** Подпись владельца — автор книги или режиссёр фильма. */
    val mediaSubtitle: String,
)

sealed interface NotesListUiState {
    data object Loading : NotesListUiState
    data class Loaded(val items: List<NoteListItem>) : NotesListUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val bookRepository: BookRepository,
    private val movieRepository: MovieRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val booksFlow = bookRepository.observeAll()
    private val moviesFlow = movieRepository.observeAll()

    // bookRepository.observeAll() hoisted out of the per-keystroke flow so a
    // search box update doesn't tear down + recreate the Room subscription.
    // Only the notes side switches between observeAll / search on query change.
    private val notesFlow = query
        .debounce(150)
        .flatMapLatest { q ->
            if (q.isBlank()) noteRepository.observeAll() else noteRepository.search(q.trim())
        }

    val uiState: StateFlow<NotesListUiState> = combine(notesFlow, booksFlow, moviesFlow) { notes, books, movies ->
        val booksById = books.associateBy { it.id }
        val moviesById = movies.associateBy { it.id }
        val items = notes
            .filter { it.kind != NoteKind.REVIEW }
            .map { note ->
                // Владелец — книга или фильм: id у них считаются независимо,
                // поэтому ищет по паре (id, тип).
                // Заголовок может быть null — владельца удалили. Текст
                // подставляет экран: в ViewModel живут строки ресурсов, а не
                // зашитый русский текст.
                val title: String?
                val subtitle: String
                if (note.mediaType == MediaType.MOVIES) {
                    val movie = moviesById[note.mediaId]
                    title = movie?.title
                    subtitle = movie?.director.orEmpty()
                } else {
                    val book = booksById[note.mediaId]
                    title = book?.title
                    subtitle = book?.author.orEmpty()
                }
                NoteListItem(
                    note = note,
                    mediaTitle = title,
                    mediaSubtitle = subtitle,
                )
            }
        NotesListUiState.Loaded(items)
    }
        .catch { e ->
            // Ошибка базы не должна выглядеть как «заметок нет»: молчаливый
            // catch прятал сломанную миграцию. Пишем в лог, что сломалось.
            Log.e(TAG, "не удалось прочитать заметки", e)
            emit(NotesListUiState.Loaded(emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesListUiState.Loading)

    val currentQuery: StateFlow<String> = query

    fun setQuery(value: String) {
        query.value = value
    }

    private companion object {
        const val TAG = "NotesListViewModel"
    }
}
