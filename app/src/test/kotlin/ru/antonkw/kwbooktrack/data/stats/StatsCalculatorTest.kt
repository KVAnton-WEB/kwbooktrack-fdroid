package ru.antonkw.kwbooktrack.data.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate

class StatsCalculatorTest {

    @Test
    fun emptyInputsYieldZeroStats() {
        val stats = StatsCalculator.compute(emptyList(), emptyList())
        assertThat(stats.totalSessions).isEqualTo(0)
        assertThat(stats.totalPages).isEqualTo(0)
        assertThat(stats.totalMinutes).isEqualTo(0)
        assertThat(stats.dailyPages).isEmpty()
        assertThat(stats.monthlyCompleted).isEmpty()
    }

    @Test
    fun dailyPagesAggregatesAcrossSessionsOnSameDay() {
        val day = LocalDate.of(2026, 9, 15)
        val ts = day.toEpochMs()
        val sessions = listOf(
            ReadingSessionEntity(bookId = 1, startedAt = ts, endPage = 50),
            ReadingSessionEntity(bookId = 1, startedAt = ts + 1000L, endPage = 80),
        )
        val stats = StatsCalculator.compute(sessions, emptyList())
        assertThat(stats.dailyPages).hasSize(1)
        // 80, not 30: the first session of a day has no predecessor *that day*,
        // but the pages before it were still read — and the app counts them
        // elsewhere as max(end_page) = 80 (BookDetailViewModel).
        assertThat(stats.dailyPages.first().pages).isEqualTo(80)
    }

    @Test
    fun pagesSurviveTheDescendingOrderTheDaoReturns() {
        // ReadingSessionDao.observeAll() is ORDER BY started_at DESC, so this is
        // the order compute() actually receives. It used to invert every delta
        // into a negative number, which .filter { it > 0 } then dropped —
        // "Страниц: 0" while the book screen showed 80.
        val day = LocalDate.of(2026, 9, 15)
        val ts = day.toEpochMs()
        val sessions = listOf(
            ReadingSessionEntity(bookId = 1, startedAt = ts + 1000L, endPage = 80),
            ReadingSessionEntity(bookId = 1, startedAt = ts, endPage = 50),
        )
        val stats = StatsCalculator.compute(sessions, emptyList())
        assertThat(stats.dailyPages.single().pages).isEqualTo(80)
        assertThat(stats.totalPages).isEqualTo(80)
    }

    @Test
    fun aSessionOnANewDayStillCountsThePagesSinceTheLastOne() {
        val day1 = LocalDate.of(2026, 9, 15)
        val day2 = LocalDate.of(2026, 9, 16)
        val sessions = listOf(
            ReadingSessionEntity(bookId = 1, startedAt = day2.toEpochMs(), endPage = 80),
            ReadingSessionEntity(bookId = 1, startedAt = day1.toEpochMs(), endPage = 50),
        )
        val stats = StatsCalculator.compute(sessions, emptyList())
        assertThat(stats.dailyPages.map { it.pages }).containsExactly(50, 30).inOrder()
        assertThat(stats.totalPages).isEqualTo(80)
    }

    @Test
    fun pagesOfDifferentBooksDoNotSubtractEachOther() {
        // end_page is a cursor into one book, so a jump from book 1 page 300 to
        // book 2 page 40 is not -260 pages of reading.
        val day = LocalDate.of(2026, 9, 15)
        val ts = day.toEpochMs()
        val sessions = listOf(
            ReadingSessionEntity(bookId = 1, startedAt = ts, endPage = 300),
            ReadingSessionEntity(bookId = 2, startedAt = ts + 1000L, endPage = 40),
        )
        val stats = StatsCalculator.compute(sessions, emptyList())
        assertThat(stats.dailyPages.single().pages).isEqualTo(340)
    }

    @Test
    fun monthlyBooksGroupsFinishedByMonth() {
        val dates = listOf(
            LocalDate.of(2026, 1, 10).toEpochMs(),
            LocalDate.of(2026, 1, 20).toEpochMs(),
            LocalDate.of(2026, 2, 5).toEpochMs(),
        )
        val stats = StatsCalculator.compute(emptyList(), dates)
        assertThat(stats.monthlyCompleted).hasSize(2)
        val jan = stats.monthlyCompleted.first { it.year == 2026 && it.month == 1 }
        val feb = stats.monthlyCompleted.first { it.year == 2026 && it.month == 2 }
        assertThat(jan.count).isEqualTo(2)
        assertThat(feb.count).isEqualTo(1)
    }

    @Test
    fun totalMinutesSumsDurationInSeconds() {
        val sessions = listOf(
            ReadingSessionEntity(bookId = 1, startedAt = 1L, endPage = 10, durationSec = 600L),
            ReadingSessionEntity(bookId = 1, startedAt = 2L, endPage = 20, durationSec = 1200L),
        )
        val stats = StatsCalculator.compute(sessions, emptyList())
        assertThat(stats.totalMinutes).isEqualTo(30)
    }
}
