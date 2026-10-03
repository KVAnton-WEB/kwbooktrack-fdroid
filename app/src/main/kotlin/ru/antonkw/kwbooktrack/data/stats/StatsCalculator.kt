package ru.antonkw.kwbooktrack.data.stats

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

data class DailyPages(val date: LocalDate, val pages: Int)

data class MonthlyBooks(val year: Int, val month: Int, val count: Int)

data class MonthlyPages(val year: Int, val month: Int, val pages: Int)

data class YearlyBooks(val year: Int, val count: Int)

data class YearlyPages(val year: Int, val pages: Int)

data class ReadingStats(
    val totalSessions: Int,
    val totalPages: Int,
    val totalMinutes: Long,
    val dailyPages: List<DailyPages>,
    val monthlyCompleted: List<MonthlyBooks>,
    val monthlyPages: List<MonthlyPages>,
    val yearlyCompleted: List<YearlyBooks>,
    val yearlyPages: List<YearlyPages>,
    val minYear: Int,
    val maxYear: Int,
)

object StatsCalculator {

    fun compute(
        sessions: List<ReadingSessionEntity>,
        finishedBookDates: List<Long>,
    ): ReadingStats {
        val dailyPages = aggregateDailyPages(sessions)
        val totalPages = dailyPages.sumOf { it.pages }
        val totalMinutes = sessions.mapNotNull { it.durationSec }.sum() / 60

        val monthly = finishedBookDates.map { ts ->
            val date = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate()
            MonthlyBooks(date.year, date.monthValue, 0)
        }.groupingBy { it.year to it.month }
            .eachCount()
            .map { (k, v) -> MonthlyBooks(k.first, k.second, v) }
            .sortedWith(compareBy({ it.year }, { it.month }))

        val monthlyPagesList = dailyPages
            .groupBy { YearMonth.from(it.date) }
            .map { (ym, list) -> MonthlyPages(ym.year, ym.monthValue, list.sumOf { it.pages }) }
            .sortedWith(compareBy({ it.year }, { it.month }))

        val yearlyCompleted = monthly
            .groupBy { it.year }
            .map { (y, list) -> YearlyBooks(y, list.sumOf { it.count }) }
            .sortedBy { it.year }

        val yearlyPages = monthlyPagesList
            .groupBy { it.year }
            .map { (y, list) -> YearlyPages(y, list.sumOf { it.pages }) }
            .sortedBy { it.year }

        val sessionYears = monthlyPagesList.map { it.year }
        val finishedYears = monthly.map { it.year }
        val allYears = sessionYears + finishedYears
        val minYear = allYears.minOrNull() ?: LocalDate.now().year
        val maxYear = allYears.maxOrNull() ?: LocalDate.now().year

        return ReadingStats(
            totalSessions = sessions.size,
            totalPages = totalPages,
            totalMinutes = totalMinutes,
            dailyPages = dailyPages,
            monthlyCompleted = monthly,
            monthlyPages = monthlyPagesList,
            yearlyCompleted = yearlyCompleted,
            yearlyPages = yearlyPages,
            minYear = minYear,
            maxYear = maxYear,
        )
    }

    fun aggregateDailyPages(sessions: List<ReadingSessionEntity>): List<DailyPages> {
        return pageDeltas(sessions)
            .groupBy({ it.first }, { it.second })
            .map { (date, pages) -> DailyPages(date, pages.sum()) }
            .sortedBy { it.date }
    }

    // end_page is an absolute cursor into the book, not a count of pages read,
    // so pages are the jump between consecutive sessions *of the same book*,
    // credited to the day of the later session. Two things this gets right that
    // a plain zipWithNext over the incoming list did not: the list is sorted
    // here (the DAO hands it over started_at DESC, which inverted every delta
    // into a negative number that .filter { it > 0 } then threw away), and the
    // baseline is the previous session of the same book rather than "no
    // previous session today" — otherwise a day with a single session read 0
    // pages. Deltas telescope, so their sum is exactly the total and
    // totalPages can keep summing the daily buckets.
    private fun pageDeltas(sessions: List<ReadingSessionEntity>): List<Pair<LocalDate, Int>> {
        return sessions
            .filter { it.endPage != null && it.startedAt > 0L }
            .groupBy { it.bookId }
            .values
            .flatMap { bookSessions ->
                val ordered = bookSessions.sortedBy { it.startedAt }
                ordered.mapIndexedNotNull { i, s ->
                    val end = s.endPage ?: return@mapIndexedNotNull null
                    val prev = ordered.getOrNull(i - 1)?.endPage ?: 0
                    val pages = end - prev
                    if (pages <= 0) null
                    else {
                        Instant.ofEpochMilli(s.startedAt).atZone(ZoneOffset.UTC)
                            .toLocalDate() to pages
                    }
                }
            }
    }

    fun lastNMonths(now: LocalDate, n: Int): List<YearMonth> {
        val current = YearMonth.of(now.year, now.month)
        return (0 until n).map { current.minusMonths(it.toLong()) }.reversed()
    }

    fun yearRange(min: Int, max: Int): List<Int> = (min..max).toList()
}

