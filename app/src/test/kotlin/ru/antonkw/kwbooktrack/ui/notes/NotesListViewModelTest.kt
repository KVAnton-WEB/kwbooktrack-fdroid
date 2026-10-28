package ru.antonkw.kwbooktrack.ui.notes

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.TestViewModels
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotesListViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var noteRepository: NoteRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            NoOpCoverStorage,
            photoRepository,
        )
        noteRepository = NoteRepository(db.noteDao(), NoOpPhotoStorage)
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun emptyDbReportsNoNotes() = runTest(dispatcher) {
        val vm = NotesListViewModel(noteRepository, bookRepository)
        TestViewModels.track(vm)
        advanceUntilIdle()
        val state = vm.uiState.first { it is NotesListUiState.Loaded } as NotesListUiState.Loaded
        assertThat(state.items).isEmpty()
    }

    @Test
    fun notesResolveBookTitleAndExcludeReviews() = runTest(dispatcher) {
        val bookId = bookRepository.insert(
            BookEntity(title = "Dune", author = "Herbert", createdAt = 0L, updatedAt = 0L),
        )
        noteRepository.insert(
            NoteEntity(bookId = bookId, bodyMd = "Note", kind = NoteKind.NOTE, createdAt = 0L, updatedAt = 0L),
        )
        noteRepository.insert(
            NoteEntity(bookId = bookId, bodyMd = "Quote", kind = NoteKind.QUOTE, createdAt = 0L, updatedAt = 0L),
        )
        noteRepository.insert(
            NoteEntity(bookId = bookId, bodyMd = "Review", kind = NoteKind.REVIEW, createdAt = 0L, updatedAt = 0L),
        )

        val vm = NotesListViewModel(noteRepository, bookRepository)
        TestViewModels.track(vm)
        advanceUntilIdle()
        val state = vm.uiState.first { it is NotesListUiState.Loaded } as NotesListUiState.Loaded
        // Review is filtered out, only Note + Quote are listed.
        assertThat(state.items).hasSize(2)
        assertThat(state.items.map { it.note.kind }).containsExactly(NoteKind.NOTE, NoteKind.QUOTE)
        assertThat(state.items.first().bookTitle).isEqualTo("Dune")
    }

    @Test
    fun deletingBookCascadesItsNotesOutOfTheList() = runTest(dispatcher) {
        // Insert a book + a note, then delete the book. FK CASCADE should
        // remove the note too — the orphan-note case isn't reachable through
        // the public API, but the CASCADE path itself must be observable.
        val bookId = bookRepository.insert(
            BookEntity(title = "Temp", author = "Anon", createdAt = 0L, updatedAt = 0L),
        )
        noteRepository.insert(
            NoteEntity(bookId = bookId, bodyMd = "to be cascaded", kind = NoteKind.NOTE, createdAt = 0L, updatedAt = 0L),
        )

        val vmBefore = NotesListViewModel(noteRepository, bookRepository)
        TestViewModels.track(vmBefore)
        advanceUntilIdle()
        val before = vmBefore.uiState.first { it is NotesListUiState.Loaded } as NotesListUiState.Loaded
        assertThat(before.items).hasSize(1)

        bookRepository.deleteById(bookId)

        val vmAfter = NotesListViewModel(noteRepository, bookRepository)
        TestViewModels.track(vmAfter)
        advanceUntilIdle()
        val after = vmAfter.uiState.first { it is NotesListUiState.Loaded } as NotesListUiState.Loaded
        assertThat(after.items).isEmpty()
    }
}