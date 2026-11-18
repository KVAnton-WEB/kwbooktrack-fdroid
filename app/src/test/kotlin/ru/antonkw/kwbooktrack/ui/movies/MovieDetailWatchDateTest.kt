package ru.antonkw.kwbooktrack.ui.movies

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
import ru.antonkw.kwbooktrack.data.media.mediaDates
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.time.toDayMs
import java.time.LocalDate

/**
 * Отметка просмотра на карточке фильма: дата добавляется, а не заменяет
 * прежние, основная — самая ранняя.
 */
@RunWith(RobolectricTestRunner::class)
class MovieDetailWatchDateTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var movies: MovieRepository
    private lateinit var mediaDates: MediaDateRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photos = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        movies = MovieRepository(db.movieDao(), NoOpCoverStorage, photos)
        mediaDates = MediaDateRepository(db.mediaDateDao())
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun markingUnwatchedMovieStoresTodayAsPrimary() = runTest(dispatcher) {
        val id = movies.insert(MovieEntity(title = "Начало", createdAt = 1L, updatedAt = 1L))
        val vm = viewModel(id)
        val today = LocalDate.of(2026, 11, 18).toDayMs()

        vm.addWatchDate(today)
        advanceUntilIdle()

        assertThat(movies.findById(id)!!.watchedAt).isEqualTo(today)
        assertThat(mediaDates.getFor(id, MediaType.MOVIES)).isEmpty()
    }

    @Test
    fun addingEarlierDateMovesPrimaryAndKeepsTheOther() = runTest(dispatcher) {
        val may = LocalDate.of(2024, 5, 15).toDayMs()
        val march = LocalDate.of(2024, 3, 10).toDayMs()
        val id = movies.insert(
            MovieEntity(title = "Малхолланд Драйв", watchedAt = may, createdAt = 1L, updatedAt = 1L),
        )
        val vm = viewModel(id)

        vm.addWatchDate(march)
        advanceUntilIdle()

        assertThat(movies.findById(id)!!.watchedAt).isEqualTo(march)
        assertThat(mediaDates.getFor(id, MediaType.MOVIES)).containsExactly(may)
        assertThat(mediaDates(movies.findById(id)!!.watchedAt, mediaDates.getFor(id, MediaType.MOVIES)))
            .containsExactly(march, may)
    }

    @Test
    fun repeatedMarkingSameDayDoesNotDuplicate() = runTest(dispatcher) {
        val day = LocalDate.of(2026, 11, 18).toDayMs()
        val id = movies.insert(
            MovieEntity(title = "Начало", watchedAt = day, createdAt = 1L, updatedAt = 1L),
        )
        val vm = viewModel(id)

        vm.addWatchDate(day)
        advanceUntilIdle()

        assertThat(movies.findById(id)!!.watchedAt).isEqualTo(day)
        assertThat(mediaDates.getFor(id, MediaType.MOVIES)).isEmpty()
    }

    private fun viewModel(movieId: Long) = MovieDetailViewModel(
        SavedStateHandle(mapOf(MovieDetailViewModel.KEY_MOVIE_ID to movieId)),
        movies,
        NoteRepository(db.noteDao(), NoOpPhotoStorage),
        PhotoRepository(db.photoDao(), NoOpPhotoStorage),
        TagRepository(db.tagDao()),
        mediaDates,
        db,
    )
}