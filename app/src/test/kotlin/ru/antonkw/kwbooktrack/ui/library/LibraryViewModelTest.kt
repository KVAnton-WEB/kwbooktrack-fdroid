package ru.antonkw.kwbooktrack.ui.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import ru.antonkw.kwbooktrack.ui.common.PeriodOption
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var repository: BookRepository
    private lateinit var movies: MovieRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var mediaTypeStore: MediaTypeStore
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        repository = BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, photoRepository)
        movies = MovieRepository(db.movieDao(), NoOpCoverStorage, photoRepository)
        sessionRepository = SessionRepository(db.readingSessionDao())
        mediaTypeStore = MediaTypeStore()
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
    ) = LibraryViewModel(repository, movies, sessionRepository, mediaTypeStore, saved).also {
        TestViewModels.track(it)
    }

    @Test
    fun initialQueryAndLayoutAreDefaults() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.currentQuery.value).isEmpty()
        assertThat(viewModel.currentLayout.value).isFalse()
        assertThat(viewModel.uiState.value).isEqualTo(LibraryUiState.Loading)
        assertThat(viewModel.currentMediaType.value).isEqualTo(MediaType.BOOKS)
    }

    @Test
    fun setQueryUpdatesCurrentQuery() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setQuery("Толкин")
        assertThat(viewModel.currentQuery.value).isEqualTo("Толкин")
    }

    @Test
    fun toggleLayoutFlipsIsGrid() = runTest(dispatcher) {
        val viewModel = viewModel()
        val before = viewModel.currentLayout.value
        viewModel.toggleLayout()
        assertThat(before).isFalse()
        assertThat(viewModel.currentLayout.value).isTrue()
    }

    @Test
    fun savedStateRestoresQuery() = runTest(dispatcher) {
        val viewModel = viewModel(androidx.lifecycle.SavedStateHandle(mapOf("query" to "Asimov")))
        assertThat(viewModel.currentQuery.value).isEqualTo("Asimov")
    }

    @Test
    fun savedStateRestoresLayout() = runTest(dispatcher) {
        val viewModel = viewModel(androidx.lifecycle.SavedStateHandle(mapOf("isGrid" to true)))
        assertThat(viewModel.currentLayout.value).isTrue()
    }

    @Test
    fun yearPeriodKeepsEveryMonthOfThatYear() = runTest(dispatcher) {
        repository.insert(purchased("Январь", LocalDate.of(2026, 1, 10)))
        repository.insert(purchased("Декабрь", LocalDate.of(2026, 12, 31)))
        repository.insert(purchased("Прошлый год", LocalDate.of(2025, 12, 31)))
        val viewModel = viewModel()
        viewModel.setBookFilter(BookFilterType.PURCHASED)
        viewModel.setPeriod(PeriodOption.year(2026))
        val loaded = viewModel.uiState.first { it is LibraryUiState.Loaded } as LibraryUiState.Loaded
        assertThat(loaded.titles()).containsExactly("Январь", "Декабрь")
    }

    @Test
    fun periodFiltersAllTypesToo() = runTest(dispatcher) {
        repository.insert(purchased("Куплена в 2026", LocalDate.of(2026, 3, 1)))
        repository.insert(purchased("Куплена в 2025", LocalDate.of(2025, 3, 1)))
        val viewModel = viewModel()
        viewModel.setPeriod(PeriodOption.year(2026))
        val loaded = viewModel.uiState.first { it is LibraryUiState.Loaded } as LibraryUiState.Loaded
        // Тип по умолчанию — «Все», но период сужает и его: остаётся книга 2026 года.
        assertThat(loaded.titles()).containsExactly("Куплена в 2026")
    }

    @Test
    fun savedStateRestoresPeriod() = runTest(dispatcher) {
        val viewModel = viewModel(
            androidx.lifecycle.SavedStateHandle(
                mapOf("periodStart" to LocalDate.of(2026, 3, 1).toEpochMs(), "periodWholeYear" to false),
            ),
        )
        val period = viewModel.currentPeriod.value
        assertThat(period.wholeYear).isFalse()
        assertThat(period.startMillis).isEqualTo(LocalDate.of(2026, 3, 1).toEpochMs())
    }

    @Test
    fun savedStateRestoresWholeYearPeriod() = runTest(dispatcher) {
        val viewModel = viewModel(
            androidx.lifecycle.SavedStateHandle(
                mapOf("periodStart" to LocalDate.of(2026, 1, 1).toEpochMs(), "periodWholeYear" to true),
            ),
        )
        assertThat(viewModel.currentPeriod.value.wholeYear).isTrue()
    }

    @Test
    fun unreadFilterKeepsEverythingButFinished() = runTest(dispatcher) {
        repository.insert(purchased("Прочитана", LocalDate.of(2026, 3, 1)).copy(status = BookStatus.FINISHED))
        repository.insert(purchased("В процессе", LocalDate.of(2026, 4, 1)).copy(status = BookStatus.READING))
        repository.insert(purchased("К прочтению", LocalDate.of(2026, 5, 1)).copy(status = BookStatus.WISHLIST))
        val viewModel = viewModel()
        viewModel.setBookFilter(BookFilterType.UNREAD)
        val loaded = viewModel.uiState.first { it is LibraryUiState.Loaded } as LibraryUiState.Loaded
        assertThat(loaded.titles()).containsExactly("В процессе", "К прочтению")
    }

    @Test
    fun switchingMediaTypeSwitchesContent() = runTest(dispatcher) {
        repository.insert(purchased("Книга", LocalDate.of(2026, 1, 1)))
        movies.insert(watched("Фильм", LocalDate.of(2026, 1, 2)))
        val viewModel = viewModel()

        viewModel.setMediaType(MediaType.MOVIES)
        val moviesLoaded = viewModel.uiState.first {
            it is LibraryUiState.Loaded && it.mediaType == MediaType.MOVIES
        } as LibraryUiState.Loaded
        assertThat(moviesLoaded.titles()).containsExactly("Фильм")

        viewModel.setMediaType(MediaType.BOOKS)
        val booksLoaded = viewModel.uiState.first {
            it is LibraryUiState.Loaded && it.mediaType == MediaType.BOOKS
        } as LibraryUiState.Loaded
        assertThat(booksLoaded.titles()).containsExactly("Книга")
    }

    @Test
    fun movieWithoutWatchedDateIsVisibleInAllFilter() = runTest(dispatcher) {
        // Фильм без даты просмотра — это фильм, который добавили вручную и не
        // смотрели. Под фильтром «Все» он должен быть виден: иначе приложение
        // говорит «в библиотеке нет фильмов», хотя фильм в базе есть.
        movies.insert(watched("Не посмотрен", null))
        val viewModel = viewModel()
        viewModel.setMediaType(MediaType.MOVIES)

        val loaded = viewModel.uiState.first {
            it is LibraryUiState.Loaded && it.mediaType == MediaType.MOVIES
        } as LibraryUiState.Loaded
        assertThat(loaded.titles()).containsExactly("Не посмотрен")
    }

    @Test
    fun filterIsActiveOnlyForCurrentMediaType() = runTest(dispatcher) {
        // Книжный фильтр не должен показывать «Сбросить» в режиме фильмов:
        // фильтры живут рядом, но не мешают друг другу.
        val viewModel = viewModel()
        viewModel.setBookFilter(BookFilterType.PURCHASED)

        viewModel.setMediaType(MediaType.MOVIES)
        val movies = viewModel.currentFilter.first { it.mediaType == MediaType.MOVIES }
        assertThat(movies.isActive).isFalse()

        viewModel.setMediaType(MediaType.BOOKS)
        val books = viewModel.currentFilter.first { it.mediaType == MediaType.BOOKS }
        assertThat(books.isActive).isTrue()
    }

    @Test
    fun movieFilterKeepsWatchedAndUnwatchedApart() = runTest(dispatcher) {
        movies.insert(watched("Посмотрен", LocalDate.of(2026, 1, 2)))
        movies.insert(watched("Не посмотрен", null))
        val viewModel = viewModel()
        viewModel.setMediaType(MediaType.MOVIES)

        viewModel.setMovieFilter(MovieFilterType.WATCHED)
        val watchedLoaded = viewModel.uiState.first {
            it is LibraryUiState.Loaded && it.filter.movieType == MovieFilterType.WATCHED
        } as LibraryUiState.Loaded
        assertThat(watchedLoaded.titles()).containsExactly("Посмотрен")

        viewModel.setMovieFilter(MovieFilterType.UNWATCHED)
        val unwatchedLoaded = viewModel.uiState.first {
            it is LibraryUiState.Loaded && it.filter.movieType == MovieFilterType.UNWATCHED
        } as LibraryUiState.Loaded
        assertThat(unwatchedLoaded.titles()).containsExactly("Не посмотрен")
    }

    @Test
    fun keepsSeparateFiltersPerMediaType() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.setBookFilter(BookFilterType.PURCHASED)
        viewModel.setMediaType(MediaType.MOVIES)
        viewModel.setMovieFilter(MovieFilterType.WATCHED)

        viewModel.setMediaType(MediaType.BOOKS)
        assertThat(viewModel.currentBookFilter.value).isEqualTo(BookFilterType.PURCHASED)
        viewModel.setMediaType(MediaType.MOVIES)
        assertThat(viewModel.currentMovieFilter.value).isEqualTo(MovieFilterType.WATCHED)
    }

    /** Названия записей списка — книги и фильмы вместе. */
    private fun LibraryUiState.Loaded.titles(): List<String> = items.map {
        when (it) {
            is LibraryItem.Book -> it.book.title
            is LibraryItem.Movie -> it.movie.title
        }
    }

    private fun purchased(title: String, date: LocalDate) = BookEntity(
        title = title,
        author = "Author",
        createdAt = 0L,
        updatedAt = 0L,
        purchaseDate = date.toEpochMs(),
    )

    private fun watched(title: String, date: LocalDate?) = MovieEntity(
        title = title,
        year = 2026,
        createdAt = 0L,
        updatedAt = 0L,
        watchedAt = date?.toEpochMs(),
    )
}
