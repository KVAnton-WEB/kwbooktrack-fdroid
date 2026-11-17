package ru.antonkw.kwbooktrack.ui.library

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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toDayMs
import ru.antonkw.kwbooktrack.ui.common.PeriodOption
import java.time.LocalDate
import java.time.YearMonth

/**
 * Фильтр по периоду при нескольких датах.
 *
 * Пересмотренный фильм смотрен и в марте, и в мае — в список за май он
 * попасть должен.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryPeriodFilterTest {

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
    fun rewatchedMovieIsFoundByTheLaterDate() = runTest(dispatcher) {
        val march = LocalDate.of(2024, 3, 10).toDayMs()
        val may = LocalDate.of(2024, 5, 15).toDayMs()
        val id = movies.insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                watchedAt = march,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        mediaDates.replaceFor(id, MediaType.MOVIES, listOf(may))
        mediaTypeStore.set(MediaType.MOVIES)

        val vm = viewModel()
        vm.setPeriod(PeriodOption.month(2024, 5, ru.antonkw.kwbooktrack.ui.common.PeriodOptions.monthRes(5)))
        advanceUntilIdle()

        // Ждём загрузки, а не непустого списка: без пересмотра фильм в мае
        // просто не попадает в список — и ждать тут было бы пустым ожиданием.
        val state = vm.uiState.first { it is LibraryUiState.Loaded }
        val items = (state as LibraryUiState.Loaded).items
        assertThat(items.map { (it as LibraryItem.Movie).movie.title })
            .containsExactly("Малхолланд Драйв")
    }

    private fun viewModel(): LibraryViewModel {
        val vm = LibraryViewModel(
            BookRepository(
                db.bookDao(),
                db.tagDao(),
                NoOpCoverStorage,
                PhotoRepository(db.photoDao(), NoOpPhotoStorage),
            ),
            movies,
            SessionRepository(db.readingSessionDao()),
            mediaDates,
            mediaTypeStore,
            androidx.lifecycle.SavedStateHandle(),
        )
        TestViewModels.track(vm)
        return vm
    }
}
