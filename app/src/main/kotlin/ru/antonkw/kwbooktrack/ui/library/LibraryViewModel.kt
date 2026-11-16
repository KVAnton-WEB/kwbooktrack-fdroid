package ru.antonkw.kwbooktrack.ui.library

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.progress.ReadingProgress
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import ru.antonkw.kwbooktrack.ui.common.PeriodOption
import ru.antonkw.kwbooktrack.ui.common.PeriodOptions
import javax.inject.Inject

/** Фильтр «Тип» для книг — по статусу прочтения. */
enum class BookFilterType(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    UNREAD(R.string.filter_unread),
    READING(R.string.filter_reading),
    FINISHED(R.string.filter_finished),
    PURCHASED(R.string.filter_purchased),
}

/**
 * Фильтр «Тип» для фильмов.
 *
 * Отдельный набор: у фильма нет статуса прочтения, есть дата просмотра.
 */
enum class MovieFilterType(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    WATCHED(R.string.filter_watched),
    UNWATCHED(R.string.filter_unwatched),
}

/** Запись списка: книга или фильм. */
sealed interface LibraryItem {
    val id: Long

    data class Book(
        val book: BookEntity,
        val pagesRead: Int,
        val progressPercent: Int,
    ) : LibraryItem {
        override val id: Long get() = book.id
    }

    data class Movie(val movie: MovieEntity) : LibraryItem {
        override val id: Long get() = movie.id
    }
}

/**
 * Состояние фильтров списка.
 *
 * Фильтры книг и фильмов живут рядом, но не мешают друг другу: переключил
 * тип носителя — вернулся к книгам, и их фильтр остался прежним.
 */
data class LibraryFilterState(
    val period: PeriodOption = PeriodOption.allTime(),
    val bookType: BookFilterType = BookFilterType.ALL,
    val movieType: MovieFilterType = MovieFilterType.ALL,
    /** Тип носителя, к которому относится содержимое списка. */
    val mediaType: MediaType = MediaType.BOOKS,
) {
    /**
     * Активен ли фильтр **для текущего типа носителя**.
     *
     * Фильтры книг и фильмов живут рядом, но не мешают друг другу: книжный
     * фильтр не должен показывать «Сбросить» в режиме фильмов — там сбрасывать
     * нечего, и кнопка только вводила бы в заблуждение.
     */
    val isActive: Boolean
        get() = !period.isAllTime || when (mediaType) {
            MediaType.BOOKS -> bookType != BookFilterType.ALL
            MediaType.MOVIES -> movieType != MovieFilterType.ALL
        }
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data class Loaded(
        val items: List<LibraryItem>,
        /** Тип носителя, к которому относится содержимое. */
        val mediaType: MediaType,
        val query: String,
        val isGrid: Boolean,
        val filter: LibraryFilterState,
    ) : LibraryUiState
}

/**
 * Список библиотеки — книги или фильмы, в зависимости от текущего типа
 * носителя ([MediaTypeStore]).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val movieRepository: MovieRepository,
    private val sessionRepository: SessionRepository,
    private val mediaTypeStore: MediaTypeStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow(savedStateHandle.get<String>(KEY_QUERY) ?: "")
    private val isGrid = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_GRID) ?: false)
    private val bookFilter = MutableStateFlow(readBookFilter())
    private val movieFilter = MutableStateFlow(readMovieFilter())
    private val period = MutableStateFlow(readPeriod())

    /** Текущий тип носителя — общий для списка, календаря и статистики. */
    val currentMediaType: StateFlow<MediaType> = mediaTypeStore.type

    /**
     * Период хранится датой начала и флагом «весь год»: из них он
     * восстанавливается однозначно. Ноль — «всё время».
     */
    private fun readPeriod(): PeriodOption {
        val start = savedStateHandle.get<Long>(KEY_PERIOD_START) ?: 0L
        if (start <= 0L) return PeriodOption.allTime()
        val date = start.toLocalDate()
        return if (savedStateHandle.get<Boolean>(KEY_PERIOD_WHOLE_YEAR) == true) {
            PeriodOption.year(date.year)
        } else {
            PeriodOption.month(date.year, date.monthValue, PeriodOptions.monthRes(date.monthValue))
        }
    }

    private fun writePeriod(option: PeriodOption) {
        savedStateHandle[KEY_PERIOD_START] = option.startMillis ?: 0L
        savedStateHandle[KEY_PERIOD_WHOLE_YEAR] = option.wholeYear
    }

    private fun readBookFilter(): BookFilterType =
        runCatching {
            BookFilterType.valueOf(savedStateHandle.get<String>(KEY_BOOK_FILTER) ?: BookFilterType.ALL.name)
        }.getOrDefault(BookFilterType.ALL)

    private fun readMovieFilter(): MovieFilterType =
        runCatching {
            MovieFilterType.valueOf(savedStateHandle.get<String>(KEY_MOVIE_FILTER) ?: MovieFilterType.ALL.name)
        }.getOrDefault(MovieFilterType.ALL)

    private fun attachProgress(
        books: List<BookEntity>,
        sessions: List<ReadingSessionEntity>,
    ): List<LibraryItem.Book> {
        val sessionsByBook = sessions.groupBy { it.bookId }
        return books.map { book ->
            val pages = ReadingProgress.pagesRead(book, sessionsByBook[book.id].orEmpty())
            val pct = if (book.pageCount != null && book.pageCount > 0) {
                (pages.coerceAtMost(book.pageCount) * 100 / book.pageCount)
            } else 0
            LibraryItem.Book(book, pages, pct)
        }
    }

    private fun applyBookFilters(books: List<BookEntity>, filters: Filters): List<BookEntity> =
        books.filter { book ->
            when (filters.bookFilter) {
                // Период сужает любой тип фильтра: «Все» — по любой дате книги,
                // «В процессе» — по дате начала чтения или покупки. Книга без
                // дат не попадает ни в какой ограниченный период.
                BookFilterType.ALL ->
                    filters.period.matchesAny(book.finishedAt, book.startedAt, book.purchaseDate)
                // «Не прочитано» — обратная сторона «Прочитано»: всё, что ещё
                // не дочитано (к прочтению, в процессе, брошено). Период для
                // такой книги — по дате начала чтения или покупки.
                BookFilterType.UNREAD ->
                    book.status != BookStatus.FINISHED &&
                        filters.period.matchesAny(book.startedAt, book.purchaseDate)
                BookFilterType.FINISHED ->
                    book.status == BookStatus.FINISHED && filters.period.matches(book.finishedAt)
                BookFilterType.READING ->
                    book.status == BookStatus.READING &&
                        filters.period.matchesAny(book.startedAt, book.purchaseDate)
                BookFilterType.PURCHASED -> filters.period.matches(book.purchaseDate)
            }
        }

    private fun applyMovieFilters(movies: List<MovieEntity>, filters: Filters): List<MovieEntity> =
        movies.filter { movie ->
            when (filters.movieFilter) {
                // Фильм без даты просмотра — это фильм, который добавили
                // вручную и ещё не смотрели: под фильтром «Все» он виден, а в
                // ограниченный период не попадает (даты нет — не сказать, что
                // он туда относится).
                MovieFilterType.ALL -> filters.period.matchesAny(movie.watchedAt)
                MovieFilterType.WATCHED ->
                    movie.watchedAt != null && filters.period.matches(movie.watchedAt)
                MovieFilterType.UNWATCHED ->
                    movie.watchedAt == null && filters.period.isAllTime
            }
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        query.debounce(150),
        bookFilter,
        movieFilter,
        period,
        isGrid,
    ) { q, bf, mf, p, grid -> Filters(q, bf, mf, p, grid) }
        .flatMapLatest { filters -> load(filters) }
        .onStart { emit(LibraryUiState.Loading) }
        .catch { e ->
            // Ошибка базы не должна выглядеть как «библиотека пуста»: молчаливый
            // catch прятал сломанную миграцию, и человек видел пустую
            // библиотеку вместо ошибки. Пишем в лог, что именно сломалось.
            Log.e(TAG, "не удалось прочитать библиотеку", e)
            emit(
                LibraryUiState.Loaded(
                    items = emptyList(),
                    mediaType = currentMediaType.value,
                    query = query.value,
                    isGrid = isGrid.value,
                    filter = filterState(),
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState.Loading)

    /** Содержимое списка — по текущему типу носителя. */
    private fun load(filters: Filters): Flow<LibraryUiState> =
        currentMediaType.flatMapLatest { type ->
            if (type == MediaType.MOVIES) movieItems(filters) else bookItems(filters)
        }

    private fun bookItems(filters: Filters): Flow<LibraryUiState> =
        combine(
            if (filters.query.isBlank()) {
                bookRepository.observeAll()
            } else {
                bookRepository.search(filters.query.trim())
            },
            sessionRepository.observeAll(),
        ) { books, sessions ->
            LibraryUiState.Loaded(
                items = attachProgress(applyBookFilters(books, filters), sessions),
                mediaType = MediaType.BOOKS,
                query = filters.query,
                isGrid = filters.isGrid,
                filter = filterState(filters),
            )
        }

    private fun movieItems(filters: Filters): Flow<LibraryUiState> =
        (if (filters.query.isBlank()) {
            movieRepository.observeAll()
        } else {
            movieRepository.search(filters.query.trim())
        })
            .map { movies ->
                LibraryUiState.Loaded(
                    items = applyMovieFilters(movies, filters).map { LibraryItem.Movie(it) },
                    mediaType = MediaType.MOVIES,
                    query = filters.query,
                    isGrid = filters.isGrid,
                    filter = filterState(filters),
                )
            }

    val currentQuery: StateFlow<String> = query
    val currentLayout: StateFlow<Boolean> = isGrid
    val currentBookFilter: StateFlow<BookFilterType> = bookFilter
    val currentMovieFilter: StateFlow<MovieFilterType> = movieFilter
    val currentPeriod: StateFlow<PeriodOption> = period

    /** Фильтры для показа: тип-специфичный фильтр берётся по текущему носителю. */
    val currentFilter: StateFlow<LibraryFilterState> =
        combine(period, bookFilter, movieFilter, currentMediaType) { p, bf, mf, mt ->
            LibraryFilterState(p, bf, mf, mt)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryFilterState())

    fun setQuery(value: String) {
        query.value = value
        savedStateHandle[KEY_QUERY] = value
    }

    fun toggleLayout() {
        isGrid.value = !isGrid.value
        savedStateHandle[KEY_GRID] = isGrid.value
    }

    fun setMediaType(type: MediaType) {
        mediaTypeStore.set(type)
    }

    fun setBookFilter(value: BookFilterType) {
        bookFilter.value = value
        savedStateHandle[KEY_BOOK_FILTER] = value.name
    }

    fun setMovieFilter(value: MovieFilterType) {
        movieFilter.value = value
        savedStateHandle[KEY_MOVIE_FILTER] = value.name
    }

    fun setPeriod(option: PeriodOption) {
        period.value = option
        writePeriod(option)
    }

    /** Сброс фильтров — только для текущего типа носителя. */
    fun clearFilters() {
        if (currentMediaType.value == MediaType.MOVIES) {
            movieFilter.value = MovieFilterType.ALL
        } else {
            bookFilter.value = BookFilterType.ALL
        }
        setPeriod(PeriodOption.allTime())
    }

    private fun filterState(filters: Filters = currentFilters()): LibraryFilterState =
        LibraryFilterState(
            filters.period,
            filters.bookFilter,
            filters.movieFilter,
            currentMediaType.value,
        )

    private fun currentFilters() = Filters(
        query.value,
        bookFilter.value,
        movieFilter.value,
        period.value,
        isGrid.value,
    )

    /** Снимок фильтров — для combine: шести потоков в один combine не положить. */
    private data class Filters(
        val query: String,
        val bookFilter: BookFilterType,
        val movieFilter: MovieFilterType,
        val period: PeriodOption,
        val isGrid: Boolean,
    )

    companion object {
        private const val TAG = "LibraryViewModel"
        private const val KEY_QUERY = "query"
        private const val KEY_GRID = "isGrid"
        private const val KEY_BOOK_FILTER = "bookFilter"
        private const val KEY_MOVIE_FILTER = "movieFilter"
        private const val KEY_PERIOD_START = "periodStart"
        private const val KEY_PERIOD_WHOLE_YEAR = "periodWholeYear"
    }
}
