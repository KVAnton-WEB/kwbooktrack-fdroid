package ru.antonkw.kwbooktrack.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.media.extrasByOwner
import ru.antonkw.kwbooktrack.data.media.mediaDates
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.data.time.toLocalDayStartMs
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarBook(
    /** id владельца: две книги с одинаковым названием — разные книги. */
    val id: Long,
    val title: String,
    val coverPath: String?,
    val rating: Int?,
)

data class CalendarDay(
    val date: LocalDate,
    val books: List<CalendarBook>,
    /** Прочитанные страницы — для книг. */
    val pages: Int = 0,
    /** Минуты просмотра — для фильмов. */
    val minutes: Int = 0,
)

/**
 * Режим ячейки календаря.
 *
 * Первый — обложки (книг или фильмов), второй — величина: страницы у книг,
 * минуты просмотра у фильмов.
 */
enum class CalendarMode { COVERS, VALUE }

data class CalendarUiState(
    val month: YearMonth,
    val days: List<CalendarDay?>,
)

/**
 * Календарь: что и сколько прочитали (или посмотрели) в каждый день.
 *
 * Тип носителя общий для всего приложения: в режиме фильмов показываются
 * фильмы и минуты просмотра, в режиме книг — книги и страницы.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val movieRepository: MovieRepository,
    private val sessionRepository: SessionRepository,
    private val mediaDateRepository: MediaDateRepository,
    private val mediaTypeStore: MediaTypeStore,
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    private val _mode = MutableStateFlow(CalendarMode.COVERS)
    val mode: StateFlow<CalendarMode> = _mode

    /** Текущий тип носителя — общий для списка, календаря и статистики. */
    val mediaType: StateFlow<MediaType> = mediaTypeStore.type

    val uiState: StateFlow<CalendarUiState> = _month.flatMapLatest { buildStateForMonth(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyState(_month.value))

    fun setMode(mode: CalendarMode) {
        _mode.value = mode
    }

    fun goToPreviousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun goToNextMonth() {
        val next = _month.value.plusMonths(1)
        val max = YearMonth.now()
        if (next <= max) _month.value = next
    }

    fun goToMonth(month: YearMonth) {
        if (month <= YearMonth.now()) _month.value = month
    }

    private fun buildStateForMonth(month: YearMonth) =
        mediaTypeStore.type.flatMapLatest { type ->
            if (type == MediaType.MOVIES) {
                combine(movieRepository.observeAll(), mediaDateRepository.observeAll()) { movies, dates ->
                    buildMonth(month, movies, extrasByOwner(dates))
                }
            } else {
                combine(
                    bookRepository.observeAll(),
                    sessionRepository.observeAll(),
                    mediaDateRepository.observeAll(),
                ) { books, sessions, dates ->
                    buildMonth(month, books, sessions, extrasByOwner(dates))
                }
            }
        }

    /** Книжный месяц: дни чтения, дочитанные книги и страницы. */
    private fun buildMonth(
        month: YearMonth,
        books: List<ru.antonkw.kwbooktrack.data.db.entity.BookEntity>,
        sessions: List<ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity>,
        extraDates: Map<Pair<Long, MediaType>, List<Long>> = emptyMap(),
    ): CalendarUiState {
        // Даты прочтения: основная плюс дополнительные (перечитывания).
        val finishedByDate = books.flatMap { book ->
            mediaDates(book.finishedAt, extraDates[book.id to MediaType.BOOKS].orEmpty())
                .map { date -> book to date }
        }
            .groupBy({ it.second.toDayDate() }, { it.first })
        val pagesByDate = StatsCalculator.aggregateDailyPages(sessions).associate { it.date to it.pages }
        val booksById = books.associateBy { it.id }
        return monthGrid(month) { date ->
            val booksForDay = (
                sessionsForDate(date, sessions, booksById) +
                    finishedByDate[date].orEmpty().map { it.toCalendarBook() }
                ).distinctBy { it.id }
            CalendarDay(date = date, books = booksForDay, pages = pagesByDate[date] ?: 0)
        }
    }

    /** Месяц фильмов: дни просмотра и минуты. */
    private fun buildMonth(
        month: YearMonth,
        movies: List<MovieEntity>,
        extraDates: Map<Pair<Long, MediaType>, List<Long>> = emptyMap(),
    ): CalendarUiState {
        // Просмотр — одна дата: пересмотренный фильм встаёт в обе даты.
        val watchedByDate = movies.flatMap { movie ->
            mediaDates(movie.watchedAt, extraDates[movie.id to MediaType.MOVIES].orEmpty())
                .map { date -> movie to date }
        }
            .groupBy({ it.second.toDayDate() }, { it.first })
        return monthGrid(month) { date ->
            val watched = watchedByDate[date].orEmpty()
            CalendarDay(
                date = date,
                // Одна плитка на фильм в день: два просмотра видно по минутам.
                books = watched.distinctBy { it.id }.map { it.toCalendarBook() },
                minutes = watched.sumOf { it.durationMin ?: 0 },
            )
        }
    }

    /**
     * Сетка месяца: пустые ячейки до первого числа, дни, пустые ячейки после
     * последнего — до полной недели.
     */
    private inline fun monthGrid(
        month: YearMonth,
        day: (LocalDate) -> CalendarDay,
    ): CalendarUiState {
        val firstDow = (month.atDay(1).dayOfWeek.value + 6) % 7
        val totalCells = firstDow + month.lengthOfMonth()
        val weeks = (totalCells + 6) / 7
        val cells = MutableList<CalendarDay?>(weeks * 7) { null }
        for (dayOfMonth in 1..month.lengthOfMonth()) {
            val date = month.atDay(dayOfMonth)
            cells[firstDow + dayOfMonth - 1] = day(date)
        }
        return CalendarUiState(month = month, days = cells)
    }

    private fun sessionsForDate(
        date: LocalDate,
        sessions: List<ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity>,
        booksById: Map<Long, ru.antonkw.kwbooktrack.data.db.entity.BookEntity>,
    ): List<CalendarBook> {
        val start = date.toLocalDayStartMs()
        val end = date.plusDays(1).toLocalDayStartMs()
        return sessions.filter { it.startedAt in start until end }
            .mapNotNull { session -> booksById[session.bookId]?.toCalendarBook() }
    }

    private fun ru.antonkw.kwbooktrack.data.db.entity.BookEntity.toCalendarBook() =
        CalendarBook(id = id, title = title, coverPath = coverPath, rating = rating)

    private fun MovieEntity.toCalendarBook() =
        CalendarBook(id = id, title = title, coverPath = coverPath, rating = rating)

    private fun emptyState(month: YearMonth): CalendarUiState =
        CalendarUiState(month = month, days = monthGrid(month) { CalendarDay(it, emptyList()) }.days)
}
