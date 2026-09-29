package ru.antonkw.kwbooktrack.ui.books

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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject

enum class BookFilterType(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    FINISHED(R.string.filter_finished),
    READING(R.string.filter_reading),
    PURCHASED(R.string.filter_purchased),
}

data class PeriodOption(
    @StringRes val labelRes: Int,
    val formatArg: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
) {
    fun matches(epochMs: Long?): Boolean {
        if (epochMs == null) return false
        if (startMillis == null || endMillis == null) return true
        return epochMs in startMillis until endMillis
    }

    val isAllTime: Boolean get() = startMillis == null

    companion object {
        fun allTime() = PeriodOption(labelRes = R.string.period_all)

        fun year(year: Int): PeriodOption {
            val start = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return PeriodOption(
                labelRes = R.string.period_year,
                formatArg = year.toString(),
                startMillis = start,
                endMillis = end,
            )
        }

        fun month(year: Int, month: Int, @StringRes monthNameRes: Int): PeriodOption {
            val start = LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = YearMonth.of(year, month).plusMonths(1)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return PeriodOption(
                labelRes = R.string.period_year_month,
                formatArg = "$year|$monthNameRes",
                startMillis = start,
                endMillis = end,
            )
        }

        fun fromDate(date: LocalDate): PeriodOption =
            month(date.year, date.monthValue, PeriodOptions.monthRes(date.monthValue))
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

    fun generate(now: LocalDate = LocalDate.now()): List<PeriodOption> {
        val currentYear = now.year
        val list = mutableListOf(PeriodOption.allTime())
        for (year in currentYear downTo currentYear - 2) {
            list.add(PeriodOption.year(year))
            for (m in 12 downTo 1) {
                list.add(PeriodOption.month(year, m, monthRes(m)))
            }
        }
        return list
    }
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow(savedStateHandle.get<String>(KEY_QUERY) ?: "")
    private val isGrid = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_GRID) ?: false)
    private val filterType = MutableStateFlow(
        runCatching { BookFilterType.valueOf(savedStateHandle.get<String>(KEY_FILTER) ?: BookFilterType.ALL.name) }
            .getOrDefault(BookFilterType.ALL)
    )
    // Compute the period list exactly once. Calling generate() twice could straddle
    // midnight and return two different lists (e.g. December → January next year),
    // and then getOrNull(idx) would silently pick the wrong option from the second list.
    private val periodOptions: List<PeriodOption> = PeriodOptions.generate()
    private val period = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_PERIOD_INDEX)?.let { periodOptions.getOrNull(it) }
            ?: periodOptions.first()
    )

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
                BookFilterType.ALL -> true
                BookFilterType.FINISHED -> book.status == BookStatus.FINISHED && p.matches(book.finishedAt)
                BookFilterType.READING -> book.status == BookStatus.READING
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
        .catch { emit(BookListUiState.Loaded(emptyList(), query.value, isGrid.value, FilterState(period.value, filterType.value))) }
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
    }

    fun toggleLayout() {
        isGrid.value = !isGrid.value
    }

    fun setFilterType(value: BookFilterType) {
        filterType.value = value
    }

    fun setPeriod(option: PeriodOption) {
        period.value = option
    }

    fun clearFilters() {
        filterType.value = BookFilterType.ALL
        period.value = PeriodOptions.generate().first()
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_GRID = "isGrid"
        private const val KEY_FILTER = "filter"
        private const val KEY_PERIOD_INDEX = "periodIdx"
    }
}
