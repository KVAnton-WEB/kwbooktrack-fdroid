package ru.antonkw.kwbooktrack.ui.movies

import androidx.lifecycle.SavedStateHandle
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
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate

/**
 * Редактор фильма: несколько дат просмотра.
 *
 * Основная дата — самая ранняя (она же уходит в Tellico), остальные — в
 * таблицу дополнительных дат.
 */
@RunWith(RobolectricTestRunner::class)
class MovieEditDatesTest {
    private lateinit var db: ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
    private lateinit var movies: MovieRepository
    private lateinit var mediaDates: MediaDateRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        movies = MovieRepository(
            db.movieDao(),
            NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
        mediaDates = MediaDateRepository(db.mediaDateDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun watchDatesAreSavedWithTheEarliestAsPrimary() = runTest(dispatcher) {
        val vm = newVm()
        vm.setTitle("Малхолланд Драйв")
        vm.setWatchedDate(0, "10.05.2024")
        vm.addWatchedDate()
        vm.setWatchedDate(1, "01.02.2023")
        vm.save()
        vm.state.first { it.saved }

        val movie = movies.getAll().single()
        // Основная дата — самая ранняя: из неё Tellico берёт первую дату.
        assertThat(movie.watchedAt).isEqualTo(LocalDate.of(2023, 2, 1).toEpochMs())
        assertThat(mediaDates.getFor(movie.id, MediaType.MOVIES))
            .containsExactly(LocalDate.of(2024, 5, 10).toEpochMs())
    }

    @Test
    fun oneDateStaysThePrimaryAndAddsNoExtraRows() = runTest(dispatcher) {
        val vm = newVm()
        vm.setTitle("Малхолланд Драйв")
        vm.setWatchedDate(0, "10.05.2024")
        vm.save()
        vm.state.first { it.saved }

        val movie = movies.getAll().single()
        assertThat(movie.watchedAt).isEqualTo(LocalDate.of(2024, 5, 10).toEpochMs())
        assertThat(mediaDates.getFor(movie.id, MediaType.MOVIES)).isEmpty()
    }

    @Test
    fun editingMovieLoadsAllWatchDates() = runTest(dispatcher) {
        val id = movies.insert(
            ru.antonkw.kwbooktrack.data.db.entity.MovieEntity(
                title = "Малхолланд Драйв",
                watchedAt = LocalDate.of(2023, 2, 1).toEpochMs(),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        mediaDates.replaceFor(
            id,
            MediaType.MOVIES,
            listOf(LocalDate.of(2024, 5, 10).toEpochMs()),
        )

        val vm = editVm(id)

        // Загрузка фильма асинхронная — ждём её.
        val state = vm.state.first { !it.loading }
        assertThat(state.watchedDates).containsExactly(
            "01.02.2023",
            "10.05.2024",
        ).inOrder()
    }

    private fun newVm() = MovieEditViewModel(
        SavedStateHandle(),
        movies,
        mediaDates,
        ApplicationProvider.getApplicationContext(),
        NoOpCoverStorage,
    )

    private fun editVm(movieId: Long) = MovieEditViewModel(
        SavedStateHandle(mapOf("movieId" to movieId)),
        movies,
        mediaDates,
        ApplicationProvider.getApplicationContext(),
        NoOpCoverStorage,
    )
}
