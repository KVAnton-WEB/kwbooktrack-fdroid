package ru.antonkw.kwbooktrack.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarBook(
    val title: String,
    val coverPath: String?,
    val rating: Int?,
)

data class CalendarDay(
    val date: LocalDate,
    val books: List<CalendarBook>,
    // Сколько страниц прочитано за этот день (сумма по всем книгам).
    val pages: Int = 0,
)

enum class CalendarMode {
    // Обложки книг, которые читались в этот день.
    BOOKS,
    // Суммарное количество прочитанных страниц за день.
    PAGES,
}

data class CalendarUiState(
    val month: YearMonth,
    val days: List<CalendarDay?>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    // Что показывает ячейка календаря: обложки книг или число страниц.
    private val _mode = MutableStateFlow(CalendarMode.BOOKS)
    val mode: StateFlow<CalendarMode> = _mode

    val uiState: StateFlow<CalendarUiState> = _month
        .flatMapLatest { ym -> buildStateForMonth(ym) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyState(_month.value))

    fun setMode(mode: CalendarMode) {
        _mode.value = mode
    }

    fun goToPreviousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun goToNextMonth() {
        val next = _month.value.plusMonths(1)
        val now = java.time.LocalDate.now()
        if (next <= java.time.YearMonth.of(now.year, now.month)) {
            _month.value = next
        }
    }

    fun goToMonth(target: java.time.YearMonth) {
        _month.value = target
    }

    private fun buildStateForMonth(ym: YearMonth) = combine(
        bookRepository.observeAll(),
        sessionRepository.observeAll(),
    ) { books, sessions ->
        val booksById = books.associateBy { it.id }
        // Прочитанные книги попадают в календарь по дате прочтения, даже если
        // по ним нет ни одной сессии чтения.
        val finishedByDate = books
            .mapNotNull { book -> book.finishedAt?.let { book to it.toLocalDate() } }
            .groupBy({ it.second }, { it.first })
        val pagesByDate = StatsCalculator.aggregateDailyPages(sessions).associate { it.date to it.pages }
        val firstOfMonth = ym.atDay(1)
        val daysInMonth = ym.lengthOfMonth()
        val firstDow = (firstOfMonth.dayOfWeek.value + 6) % 7 // Mon=0 ... Sun=6
        val totalCells = firstDow + daysInMonth
        val weeks = (totalCells + 6) / 7

        val cells = ArrayList<CalendarDay?>(weeks * 7)
        repeat(firstDow) { cells.add(null) }
        for (day in 1..daysInMonth) {
            val date = ym.atDay(day)
            val booksForDay = (
                sessionsForDate(date, sessions, booksById) +
                    finishedByDate[date].orEmpty().map { it.toCalendarBook() }
                ).distinctBy { it.title }
            cells.add(CalendarDay(date, booksForDay, pagesByDate[date] ?: 0))
        }
        while (cells.size < weeks * 7) cells.add(null)

        CalendarUiState(
            month = ym,
            days = cells,
        )
    }

    private fun sessionsForDate(
        date: LocalDate,
        sessions: List<ReadingSessionEntity>,
        booksById: Map<Long, BookEntity>,
    ): List<CalendarBook> {
        val startMs = date.toEpochMs()
        val endMs = date.plusDays(1).toEpochMs()
        return sessions
            .filter { it.startedAt in startMs until endMs }
            .mapNotNull { session ->
                val book = booksById[session.bookId] ?: return@mapNotNull null
                book.toCalendarBook()
            }
            .distinctBy { it.title }
    }

    private fun BookEntity.toCalendarBook() = CalendarBook(
        title = title,
        coverPath = coverPath,
        rating = rating,
    )

    private fun emptyState(ym: YearMonth): CalendarUiState {
        val firstOfMonth = ym.atDay(1)
        val firstDow = (firstOfMonth.dayOfWeek.value + 6) % 7
        val daysInMonth = ym.lengthOfMonth()
        val cells = ArrayList<CalendarDay?>(firstDow + daysInMonth)
        repeat(firstDow) { cells.add(null) }
        for (day in 1..daysInMonth) cells.add(CalendarDay(ym.atDay(day), emptyList()))
        return CalendarUiState(month = ym, days = cells)
    }
}
