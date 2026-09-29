package ru.antonkw.kwbooktrack.ui.calendar

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
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CalendarViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: SessionRepository
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
        sessionRepository = SessionRepository(db.readingSessionDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun emptySessionsYieldsEmptyDaysButCorrectShape() = runTest(dispatcher) {
        val vm = CalendarViewModel(bookRepository, sessionRepository)
        advanceUntilIdle()
        val state = vm.uiState.first { it.days.size >= 28 } // any month has >= 28 days
        assertThat(state.totalBooksThisMonth).isEqualTo(0)
        assertThat(state.days.filterNotNull().flatMap { it.books }).isEmpty()
        // First of month is positioned so the grid has a non-null Monday-Sunday block.
        assertThat(state.days.firstOrNull { it != null }?.date).isEqualTo(
            state.month.atDay(1),
        )
    }

    @Test
    fun sessionOnDayShowsBookInThatCell() = runTest(dispatcher) {
        val vm = CalendarViewModel(bookRepository, sessionRepository)
        val firstOfMonth = vm.month.value.atDay(1)
        val bookId = bookRepository.insert(
            BookEntity(title = "Hyperion", author = "Simmons", createdAt = 0L, updatedAt = 0L),
        )
        val midnight = firstOfMonth.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        sessionRepository.insert(
            ReadingSessionEntity(bookId = bookId, startedAt = midnight, endPage = 50),
        )

        advanceUntilIdle()
        val state = vm.uiState.first { it.totalBooksThisMonth == 1 }
        val day1 = state.days.firstOrNull { it?.date == firstOfMonth }
        assertThat(day1?.books?.map { it.title }).containsExactly("Hyperion")
    }
}