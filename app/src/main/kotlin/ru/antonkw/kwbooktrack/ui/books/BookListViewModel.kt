package ru.antonkw.kwbooktrack.ui.books

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class BookFilterType(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    UNREAD(R.string.filter_unread),
    READING(R.string.filter_reading),
    FINISHED(R.string.filter_finished),
    PURCHASED(R.string.filter_purchased),
}

data class PeriodOption(
    @StringRes val labelRes: Int,
    val formatArg: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    /** Период покрывает целый год, а не один месяц. */
    val wholeYear: Boolean = false,
) {
    /**
     * Дата попадает в период. Книга без этой даты не подходит ни под один
     * период, включая «Всё время» — дата нужна, чтобы сказать «эта книга».
     */
    fun matches(epochMs: Long?): Boolean {
        if (epochMs == null) return false
        if (startMillis == null || endMillis == null) return true
        return epochMs in startMillis until endMillis
    }

    /**
     * Книга подходит под период, если в диапазон попала любая из её дат.
     * Книги без дат в период не попадают — как и в [matches].
     */
    fun matchesAny(vararg dates: Long?): Boolean {
        val start = startMillis ?: return true
        val end = endMillis ?: return true
        return dates.any { it != null && it in start until end }
    }

    val isAllTime: Boolean get() = startMillis == null

    companion object {
        fun allTime() = PeriodOption(labelRes = R.string.period_all)

        fun year(year: Int): PeriodOption {
            val start = LocalDate.of(year, 1, 1).toEpochMs()
            val end = LocalDate.of(year + 1, 1, 1).toEpochMs()
            return PeriodOption(
                labelRes = R.string.period_year,
                formatArg = year.toString(),
                startMillis = start,
                endMillis = end,
                wholeYear = true,
            )
        }

        fun month(year: Int, month: Int, @StringRes monthNameRes: Int): PeriodOption {
            val start = LocalDate.of(year, month, 1).toEpochMs()
            val end = YearMonth.of(year, month).plusMonths(1).atDay(1).toEpochMs()
            return PeriodOption(
                labelRes = R.string.period_year_month,
                formatArg = "$year|$monthNameRes",
                startMillis = start,
                endMillis = end,
            )
        }
    }
}

object PeriodOptions {
    @StringRes
    private val monthResIds = listOf(
        R.string.month_jan, R.string.month_feb, R.string.month_mar,
        R.string.month_apr, R.string.month_may, R.string.month_jun,
        R.string.month_jul, R.string.month_aug, R.string.month_sep,
        R.string.month_oct, R.string.month_nov, R.string.month_dec,
    )

    @StringRes
    fun monthRes(m: Int): Int = monthResIds[m - 1]
}

data class BookWithProgress(
    val book: BookEntity,
    val pagesRead: Int,
    val progressPercent: Int,
)

data class FilterState(
    val period: PeriodOption = PeriodOption.allTime(),
    val type: BookFilterType = BookFilterType.ALL,
) {
    val isActive: Boolean get() = !period.isAllTime || type != BookFilterType.ALL
}

sealed interface BookListUiState {
    data object Loading : BookListUiState
    data class Loaded(
        val books: List<BookWithProgress>,
        val query: String,
        val isGrid: Boolean,
        val filter: FilterState,
    ) : BookListUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookListViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val sessionRepository: SessionRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow(savedStateHandle.get<String>(KEY_QUERY) ?: "")
    private val isGrid = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_GRID) ?: false)
    private val filterType = MutableStateFlow(
        runCatching { BookFilterType.valueOf(savedStateHandle.get<String>(KEY_FILTER) ?: BookFilterType.ALL.name) }
            .getOrDefault(BookFilterType.ALL)
    )
    private val period = MutableStateFlow(readPeriod())

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

    private fun attachProgress(
        books: List<BookEntity>,
        sessions: List<ReadingSessionEntity>,
    ): List<BookWithProgress> {
        val pagesByBook = sessions.groupBy { it.bookId }
            .mapValues { (_, list) -> list.mapNotNull { it.endPage }.maxOrNull() ?: 0 }
        return books.map { book ->
            val pages = pagesByBook[book.id] ?: 0
            val pct = if (book.pageCount != null && book.pageCount > 0) {
                (pages.coerceAtMost(book.pageCount) * 100 / book.pageCount)
            } else 0
            BookWithProgress(book, pages, pct)
        }
    }

    private fun applyFilters(books: List<BookEntity>): List<BookEntity> {
        val p = period.value
        val t = filterType.value
        return books.filter { book ->
            when (t) {
                // Период сужает любой тип фильтра: «Все» — по любой дате книги,
                // «В процессе» — по дате начала чтения или покупки. Книга без
                // дат не попадает ни в какой ограниченный период.
                BookFilterType.ALL ->
                    p.matchesAny(book.finishedAt, book.startedAt, book.purchaseDate)
                // «Не прочитано» — обратная сторона «Прочитано»: всё, что ещё
                // не дочитано (к прочтению, в процессе, брошено). Период для
                // такой книги — по дате начала чтения или покупки.
                BookFilterType.UNREAD ->
                    book.status != BookStatus.FINISHED &&
                        p.matchesAny(book.startedAt, book.purchaseDate)
                BookFilterType.FINISHED ->
                    book.status == BookStatus.FINISHED && p.matches(book.finishedAt)
                BookFilterType.READING ->
                    book.status == BookStatus.READING &&
                        p.matchesAny(book.startedAt, book.purchaseDate)
                BookFilterType.PURCHASED -> p.matches(book.purchaseDate)
            }
        }
    }

    val uiState: StateFlow<BookListUiState> = combine(
        query.debounce(150),
        filterType,
        period,
        isGrid,
        sessionRepository.observeAll(),
    ) { q, type, p, grid, sessions ->
        val baseFlow = if (q.isBlank()) bookRepository.observeAll() else bookRepository.search(q.trim())
        baseFlow.map { all ->
            val filtered = applyFilters(all)
            BookListUiState.Loaded(
                books = attachProgress(filtered, sessions),
                query = q,
                isGrid = grid,
                filter = FilterState(p, type),
            ) as BookListUiState
        }
    }
        .flatMapLatest { it }
        .onStart { emit(BookListUiState.Loading as BookListUiState) }
        .catch { e ->
            // Ошибка базы не должна выглядеть как «библиотека пуста»: молчаливый
            // catch прятал сломанную миграцию, и человек видел пустую
            // библиотеку вместо ошибки. Пишем в лог, что именно сломалось.
            Log.e(TAG, "не удалось прочитать библиотеку", e)
            emit(BookListUiState.Loaded(emptyList(), query.value, isGrid.value, FilterState(period.value, filterType.value)))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BookListUiState.Loading)

    val currentQuery: StateFlow<String> = query
    val currentLayout: StateFlow<Boolean> = isGrid
    val currentFilterType: StateFlow<BookFilterType> = filterType
    val currentPeriod: StateFlow<PeriodOption> = period
    val currentFilter: StateFlow<FilterState> = combine(period, filterType) { p, t ->
        FilterState(p, t)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FilterState())

    fun setQuery(value: String) {
        query.value = value
        savedStateHandle[KEY_QUERY] = value
    }

    fun toggleLayout() {
        isGrid.value = !isGrid.value
        savedStateHandle[KEY_GRID] = isGrid.value
    }

    fun setFilterType(value: BookFilterType) {
        filterType.value = value
        savedStateHandle[KEY_FILTER] = value.name
    }

    fun setPeriod(option: PeriodOption) {
        period.value = option
        writePeriod(option)
    }

    fun clearFilters() {
        filterType.value = BookFilterType.ALL
        setPeriod(PeriodOption.allTime())
    }

    companion object {
        private const val TAG = "BookListViewModel"
        private const val KEY_QUERY = "query"
        private const val KEY_GRID = "isGrid"
        private const val KEY_FILTER = "filter"
        private const val KEY_PERIOD_START = "periodStart"
        private const val KEY_PERIOD_WHOLE_YEAR = "periodWholeYear"
    }
}
