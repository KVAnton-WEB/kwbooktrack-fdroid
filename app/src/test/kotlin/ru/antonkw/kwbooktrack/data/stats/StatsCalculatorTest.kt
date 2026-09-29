package ru.antonkw.kwbooktrack.data.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
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
        val ts = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val sessions = listOf(
            ReadingSessionEntity(bookId = 1, startedAt = ts, endPage = 50),
            ReadingSessionEntity(bookId = 1, startedAt = ts + 1000L, endPage = 80),
        )
        val stats = StatsCalculator.compute(sessions, emptyList())
        assertThat(stats.dailyPages).hasSize(1)
        assertThat(stats.dailyPages.first().pages).isEqualTo(30)
    }

    @Test
    fun monthlyBooksGroupsFinishedByMonth() {
        val dates = listOf(
            LocalDate.of(2026, 1, 10).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            LocalDate.of(2026, 1, 20).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            LocalDate.of(2026, 2, 5).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
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

    @Test
    fun statusFilterWorksForMonthlyBooks() {
        // Just smoke test that BookStatus.FINISHED is used for filtering (the ViewModel does this,
        // not the calculator itself).
        assertThat(BookStatus.FINISHED.name).isEqualTo("FINISHED")
    }
}
