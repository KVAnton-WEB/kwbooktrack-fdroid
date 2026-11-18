package ru.antonkw.kwbooktrack.ui.books

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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toDayMs
import java.time.LocalDate

/**
 * Карточка книги: несколько дат прочтения.
 *
 * «Отметить прочитанным» добавляет дату, а не заменяет: книга, прочитанная в
 * январе и отмеченная прочитанной в ноябре, сохраняет обе даты.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookDetailMultiDateTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var mediaDates: MediaDateRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photos = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, photos)
        mediaDates = MediaDateRepository(db.mediaDateDao())
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun cardShowsAllReadDates() = runTest(dispatcher) {
        val id = bookRepository.insert(
            BookEntity(
                title = "Дюна",
                author = "Герберт",
                finishedAt = LocalDate.of(2023, 2, 1).toDayMs(),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        mediaDates.replaceFor(id, MediaType.BOOKS, listOf(LocalDate.of(2024, 5, 10).toDayMs()))

        val vm = viewModelFor(id)
        val state = vm.uiState.first { it.dates.size == 2 }

        assertThat(state.dates).containsExactly(
            LocalDate.of(2023, 2, 1).toDayMs(),
            LocalDate.of(2024, 5, 10).toDayMs(),
        ).inOrder()
    }

    @Test
    fun markingAsReadAddsADateAndKeepsTheEarlierOne() = runTest(dispatcher) {
        val january = LocalDate.of(2023, 2, 1).toDayMs()
        val id = bookRepository.insert(
            BookEntity(
                title = "Дюна",
                author = "Герберт",
                finishedAt = january,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val vm = viewModelFor(id)
        vm.markFinished(null)
        advanceUntilIdle()

        val book = bookRepository.findById(id)!!
        // Основная дата — самая ранняя, январская не пропала.
        assertThat(book.finishedAt).isEqualTo(january)
        assertThat(mediaDates.getFor(id, MediaType.BOOKS)).hasSize(1)
    }

    @Test
    fun markingAsReadKeepsEarlierRereadDates() = runTest(dispatcher) {
        // Книга прочитана в январе и перечитана в марте. «Отметить прочитанным»
        // не должен затирать дату перечитывания.
        val january = LocalDate.of(2023, 2, 1).toDayMs()
        val march = LocalDate.of(2023, 3, 5).toDayMs()
        val id = bookRepository.insert(
            BookEntity(
                title = "Дюна",
                author = "Герберт",
                finishedAt = january,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        mediaDates.replaceFor(id, MediaType.BOOKS, listOf(march))

        val vm = viewModelFor(id)
        vm.markFinished(null)
        advanceUntilIdle()

        assertThat(mediaDates.getFor(id, MediaType.BOOKS)).contains(march)
    }

    private fun viewModelFor(bookId: Long): BookDetailViewModel {
        val vm = BookDetailViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("bookId" to bookId)),
            bookRepository,
            SessionRepository(db.readingSessionDao()),
            ru.antonkw.kwbooktrack.data.repository.NoteRepository(db.noteDao(), NoOpPhotoStorage),
            PhotoRepository(db.photoDao(), NoOpPhotoStorage),
            ru.antonkw.kwbooktrack.data.repository.TagRepository(db.tagDao()),
            mediaDates,
            db,
        )
        TestViewModels.track(vm)
        return vm
    }
}
