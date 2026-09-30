package ru.antonkw.kwbooktrack.ui.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookDetailViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var photoRepository: PhotoRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage, photoRepository)
        sessionRepository = SessionRepository(db.readingSessionDao())
        noteRepository = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateHasNullBook() = runTest(dispatcher) {
        val viewModel = BookDetailViewModel(
            androidx.lifecycle.SavedStateHandle(),
            bookRepository,
            sessionRepository,
            noteRepository,
            photoRepository,
            NoOpPhotoStorage,
        )
        assertThat(viewModel.uiState.value.book).isNull()
    }

    @Test
    fun setCurrentPageInsertsSession() = runBlocking {
        val bookId = bookRepository.insert(sampleBook("Dune", pageCount = 500))

        val viewModel = BookDetailViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("bookId" to bookId)),
            bookRepository,
            sessionRepository,
            noteRepository,
            photoRepository,
            NoOpPhotoStorage,
        )
        viewModel.setCurrentPage(150)

        val sessions = db.readingSessionDao().observeByBook(bookId).first()
        assertThat(sessions).hasSize(1)
        assertThat(sessions.first().endPage).isEqualTo(150)
    }

    @Test
    fun setCurrentPageTransitionsWishlistToReading() = runBlocking {
        val bookId = bookRepository.insert(sampleBook("Dune", pageCount = 500, status = BookStatus.WISHLIST))

        val viewModel = BookDetailViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("bookId" to bookId)),
            bookRepository,
            sessionRepository,
            noteRepository,
            photoRepository,
            NoOpPhotoStorage,
        )
        viewModel.setCurrentPage(50)

        val updated = bookRepository.findById(bookId)!!
        assertThat(updated.status).isEqualTo(BookStatus.READING)
        assertThat(updated.startedAt).isNotNull()
    }

    @Test
    fun markFinishedSetsFinishedAt() = runBlocking {
        val bookId = bookRepository.insert(sampleBook("Dune", pageCount = 500))

        val viewModel = BookDetailViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("bookId" to bookId)),
            bookRepository,
            sessionRepository,
            noteRepository,
            photoRepository,
            NoOpPhotoStorage,
        )
        viewModel.markFinished(rating = 5)

        val updated = bookRepository.findById(bookId)!!
        assertThat(updated.status).isEqualTo(BookStatus.FINISHED)
        assertThat(updated.finishedAt).isNotNull()
        assertThat(updated.rating).isEqualTo(5)
    }

    @Test
    fun deleteBookCascadesSessionsAndNotes() = runTest(dispatcher) {
        val bookId = bookRepository.insert(sampleBook("Dune", pageCount = 500))
        viewModelForBook(bookId).setCurrentPage(120)
        advanceUntilIdle()
        noteRepository.insert(
            ru.antonkw.kwbooktrack.data.db.entity.NoteEntity(
                bookId = bookId,
                bodyMd = "test",
                kind = ru.antonkw.kwbooktrack.data.db.entity.NoteKind.NOTE,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        val viewModel = viewModelForBook(bookId)
        viewModel.deleteBook()
        advanceUntilIdle()
        // viewModel.deleteBook uses viewModelScope.launch which runs on the Main
        // dispatcher — under runTest with StandardTestDispatcher, the launched
        // coroutine still gets scheduled on the test scheduler and finishes with
        // advanceUntilIdle, but Room's invalidate path can still need one extra
        // tick for observers to see the delete. Yield until the book is gone.
        while (bookRepository.findById(bookId) != null) {
            advanceUntilIdle()
        }

        assertThat(bookRepository.findById(bookId)).isNull()
        assertThat(db.readingSessionDao().observeByBook(bookId).first()).isEmpty()
        assertThat(db.noteDao().observeByBook(bookId).first()).isEmpty()
    }

    private fun viewModelForBook(bookId: Long) = BookDetailViewModel(
        androidx.lifecycle.SavedStateHandle(mapOf("bookId" to bookId)),
        bookRepository,
        sessionRepository,
        noteRepository,
        photoRepository,
        NoOpPhotoStorage,
    )

    private fun sampleBook(
        title: String,
        pageCount: Int? = null,
        status: BookStatus = BookStatus.WISHLIST,
    ) = BookEntity(
        title = title,
        author = "Anon",
        pageCount = pageCount,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
