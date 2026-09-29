package ru.antonkw.kwbooktrack.ui.notes

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
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import javax.inject.Inject

data class NoteListItem(
    val note: NoteEntity,
    val bookTitle: String,
    val author: String,
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
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val booksFlow = bookRepository.observeAll()

    // bookRepository.observeAll() hoisted out of the per-keystroke flow so a
    // search box update doesn't tear down + recreate the Room subscription.
    // Only the notes side switches between observeAll / search on query change.
    private val notesFlow = query
        .debounce(150)
        .flatMapLatest { q ->
            if (q.isBlank()) noteRepository.observeAll() else noteRepository.search(q.trim())
        }

    val uiState: StateFlow<NotesListUiState> = combine(notesFlow, booksFlow) { notes, books ->
        val booksById = books.associateBy { it.id }
        val items = notes
            .filter { it.kind != NoteKind.REVIEW }
            .map { note ->
                val book = booksById[note.bookId]
                NoteListItem(
                    note = note,
                    bookTitle = book?.title ?: "(книга удалена)",
                    author = book?.author.orEmpty(),
                )
            }
        NotesListUiState.Loaded(items) as NotesListUiState
    }
        .catch { emit(NotesListUiState.Loaded(emptyList())) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NotesListUiState.Loading)

    val currentQuery: StateFlow<String> = query

    fun setQuery(value: String) {
        query.value = value
    }
}
