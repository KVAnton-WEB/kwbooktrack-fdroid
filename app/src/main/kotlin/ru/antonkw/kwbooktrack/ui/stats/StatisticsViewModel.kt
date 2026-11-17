package ru.antonkw.kwbooktrack.ui.stats

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.media.extrasByOwner
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.stats.MovieStats
import ru.antonkw.kwbooktrack.data.stats.MovieStatsCalculator
import ru.antonkw.kwbooktrack.data.stats.ReadingStats
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
import java.time.LocalDate
import javax.inject.Inject

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

/**
 * Агрегаты статистики — по текущему типу носителя.
 *
 * Книга считается в страницах и сессиях, фильм — в минутах просмотра и
 * количестве фильмов.
 */
sealed interface StatisticsData {
    data class Books(val stats: ReadingStats) : StatisticsData
    data class Movies(val stats: MovieStats) : StatisticsData
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    bookRepository: BookRepository,
    movieRepository: MovieRepository,
    sessionRepository: SessionRepository,
    private val mediaDateRepository: MediaDateRepository,
    mediaTypeStore: MediaTypeStore,
) : ViewModel() {

    private val _selection = MutableStateFlow(ChartSelection())
    val selection: StateFlow<ChartSelection> = _selection

    /** Текущий тип носителя — общий для списка, календаря и статистики. */
    val mediaType: StateFlow<MediaType> = mediaTypeStore.type

    val stats: StateFlow<StatisticsData> = mediaTypeStore.type.flatMapLatest { type ->
        if (type == MediaType.MOVIES) {
            combine(movieRepository.observeAll(), mediaDateRepository.observeAll()) { movies, dates ->
                // Дополнительные даты просмотра — по id фильма.
                val byMovie = dates
                    .filter { it.mediaType == MediaType.MOVIES }
                    .groupBy({ it.mediaId }, { it.date })
                StatisticsData.Movies(MovieStatsCalculator.compute(movies, byMovie))
            }
        } else {
            combine(
                bookRepository.observeAll(),
                sessionRepository.observeAll(),
                mediaDateRepository.observeAll(),
            ) { books, sessions, dates ->
                val byBook = dates
                    .filter { it.mediaType == MediaType.BOOKS }
                    .groupBy({ it.mediaId }, { it.date })
                // Даты прочтения: основная плюс дополнительные (перечитывания).
                val finishedDates = books.flatMap { book ->
                    val primary = if (book.status == BookStatus.FINISHED) {
                        listOfNotNull(book.finishedAt)
                    } else {
                        emptyList()
                    }
                    primary + byBook[book.id].orEmpty()
                }
                StatisticsData.Books(StatsCalculator.compute(sessions, finishedDates, books))
            }
        }
    }
        // StatsCalculator walks every session twice (Instant/LocalDate per row),
        // so keep it off the main thread — it re-runs on every book/session
        // write anywhere in the app, not just when this screen is visible.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StatisticsData.Books(ReadingStats()))

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
        val min = when (val data = stats.value) {
            is StatisticsData.Books -> data.stats.minYear.takeIf { it > 0 } ?: today
            is StatisticsData.Movies -> data.stats.minYear.takeIf { it > 0 } ?: today
        }
        return Bounds(min, maxOf(min, today))
    }
}
