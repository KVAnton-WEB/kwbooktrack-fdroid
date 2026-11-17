package ru.antonkw.kwbooktrack.ui.stats

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
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toDayMs
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatisticsViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var movieRepository: MovieRepository
    private lateinit var mediaDates: MediaDateRepository
    private lateinit var mediaTypeStore: MediaTypeStore
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
        movieRepository = MovieRepository(db.movieDao(), NoOpCoverStorage, photoRepository)
        mediaDates = MediaDateRepository(db.mediaDateDao())
        mediaTypeStore = MediaTypeStore()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun emptyInputsYieldZeroStats() = runTest(dispatcher) {
        val vm = StatisticsViewModel(bookRepository, movieRepository, sessionRepository, mediaDates, mediaTypeStore)
        advanceUntilIdle()
        val stats = vm.stats.first { it is StatisticsData.Books && it.stats.totalSessions == 0 }
            .let { (it as StatisticsData.Books).stats }
        assertThat(stats.totalSessions).isEqualTo(0)
        assertThat(stats.totalPages).isEqualTo(0)
    }

    @Test
    fun statsReflectInsertedBooksAndSessions() = runTest(dispatcher) {
        val today = LocalDate.now().toDayMs()
        bookRepository.insert(
            BookEntity(
                title = "Dune",
                author = "Herbert",
                status = BookStatus.FINISHED,
                finishedAt = today,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        sessionRepository.insert(
            ReadingSessionEntity(bookId = 1L, startedAt = today, endPage = 100, durationSec = 600L),
        )

        val vm = StatisticsViewModel(bookRepository, movieRepository, sessionRepository, mediaDates, mediaTypeStore)
        advanceUntilIdle()
        val stats = vm.stats.first { it is StatisticsData.Books && it.stats.totalSessions == 1 }
            .let { (it as StatisticsData.Books).stats }
        assertThat(stats.totalSessions).isEqualTo(1)
        assertThat(stats.monthlyCompleted).isNotEmpty()
    }

    @Test
    fun setModeAndStepYearRoundTrip() = runTest(dispatcher) {
        val vm = StatisticsViewModel(bookRepository, movieRepository, sessionRepository, mediaDates, mediaTypeStore)
        advanceUntilIdle()

        vm.setMode(ChartMode.BY_YEAR)
        assertThat(vm.selection.value.mode).isEqualTo(ChartMode.BY_YEAR)
        vm.setMode(ChartMode.BY_MONTH)
        assertThat(vm.selection.value.mode).isEqualTo(ChartMode.BY_MONTH)
    }
}