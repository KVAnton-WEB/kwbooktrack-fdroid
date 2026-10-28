package ru.antonkw.kwbooktrack.ui.stats

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.stats.ReadingStats
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
import java.time.LocalDate
import javax.inject.Inject

enum class ChartMetric { PAGES, BOOKS }
enum class ChartMode { BY_MONTH, BY_YEAR }

data class ChartSelection(
    val mode: ChartMode = ChartMode.BY_MONTH,
    val year: Int = LocalDate.now().year,
) {
    /** Локализованный идентификатор заголовка периода. */
    @StringRes
    fun labelRes(): Int = when (mode) {
        ChartMode.BY_YEAR -> R.string.stats_label_all_years
        ChartMode.BY_MONTH -> R.string.period_year
    }

    /** Аргументы форматирования для [labelRes]. */
    val labelArgs: List<Any>
        get() = when (mode) {
            ChartMode.BY_YEAR -> emptyList()
            ChartMode.BY_MONTH -> listOf(year)
        }
}

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    bookRepository: BookRepository,
    sessionRepository: SessionRepository,
) : ViewModel() {

    private val _selection = MutableStateFlow(ChartSelection())
    val selection: StateFlow<ChartSelection> = _selection

    val stats: StateFlow<ReadingStats> = combine(
        bookRepository.observeAll(),
        sessionRepository.observeAll(),
    ) { books, sessions ->
        val finishedDates = books.filter { it.status == BookStatus.FINISHED && it.finishedAt != null }
            .mapNotNull { it.finishedAt }
        StatsCalculator.compute(sessions, finishedDates, books)
    }
        // StatsCalculator walks every session twice (Instant/LocalDate per row),
        // so keep it off the main thread — it re-runs on every book/session
        // write anywhere in the app, not just when this screen is visible.
        .flowOn(Dispatchers.Default)
        .stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ReadingStats(
            totalSessions = 0,
            totalPages = 0,
            dailyPages = emptyList(),
            monthlyCompleted = emptyList(),
            monthlyPages = emptyList(),
            monthlyPurchased = emptyList(),
            monthlySpend = emptyList(),
            yearlyCompleted = emptyList(),
            yearlyPages = emptyList(),
            yearlyPurchased = emptyList(),
            yearlySpend = emptyList(),
            minYear = 0,
            maxYear = 0,
        ),
    )

    fun setMode(mode: ChartMode) {
        _selection.value = _selection.value.copy(mode = mode)
    }

    fun stepYear(delta: Int) {
        val s = _selection.value
        val bounds = bounds()
        val target = s.year + delta
        if (target < bounds.firstYear || target > bounds.lastYear) return
        _selection.value = s.copy(year = target)
    }

    fun resetPeriod() {
        _selection.value = _selection.value.copy(year = LocalDate.now().year)
    }

    data class Bounds(val firstYear: Int, val lastYear: Int)

    private fun bounds(): Bounds {
        val today = LocalDate.now().year
        val min = stats.value.minYear.takeIf { it > 0 } ?: today
        return Bounds(min, maxOf(min, today))
    }
}
