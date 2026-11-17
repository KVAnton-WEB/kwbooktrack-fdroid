package ru.antonkw.kwbooktrack.ui.calendar

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate

/**
 * Календарь фильмов с несколькими датами просмотра.
 *
 * Пересмотренный фильм стоит в ячейке каждой своей даты, минуты в ячейке —
 * сумма по просмотрам за день.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarMultiDateTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var movies: MovieRepository
    private lateinit var mediaDates: MediaDateRepository
    private lateinit var mediaTypeStore: MediaTypeStore
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        movies = MovieRepository(
            db.movieDao(),
            NoOpCoverStorage,
            PhotoRepository(db.photoDao(), NoOpPhotoStorage),
        )
        mediaDates = MediaDateRepository(db.mediaDateDao())
        mediaTypeStore = MediaTypeStore()
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun rewatchedMovieStandsInBothDates() = runTest(dispatcher) {
        val first = LocalDate.of(2024, 2, 10).toEpochMs()
        val second = LocalDate.of(2024, 3, 15).toEpochMs()
        val id = movies.insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                durationMin = 120,
                watchedAt = first,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        mediaDates.replaceFor(id, MediaType.MOVIES, listOf(second))
        mediaTypeStore.set(MediaType.MOVIES)

        val vm = CalendarViewModel(
            BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, PhotoRepository(db.photoDao(), NoOpPhotoStorage)),
            movies,
            SessionRepository(db.readingSessionDao()),
            mediaDates,
            mediaTypeStore,
        )
        TestViewModels.track(vm)
        vm.goToMonth(java.time.YearMonth.of(2024, 2))
        advanceUntilIdle()

        val february = vm.uiState.first { it.month == java.time.YearMonth.of(2024, 2) }
        val februaryDay = february.days.first { it?.date == LocalDate.of(2024, 2, 10) }
        assertThat(februaryDay!!.books.map { it.title }).containsExactly("Малхолланд Драйв")
        assertThat(februaryDay.minutes).isEqualTo(120)

        vm.goToMonth(java.time.YearMonth.of(2024, 3))
        advanceUntilIdle()
        val march = vm.uiState.first { it.month == java.time.YearMonth.of(2024, 3) }
        val marchDay = march.days.first { it?.date == LocalDate.of(2024, 3, 15) }
        assertThat(marchDay!!.books.map { it.title }).containsExactly("Малхолланд Драйв")
        assertThat(marchDay.minutes).isEqualTo(120)
    }
}
