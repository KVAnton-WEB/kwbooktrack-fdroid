package ru.antonkw.kwbooktrack.data.stats

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.time.LocalDate
import java.time.YearMonth

data class DailyPages(val date: LocalDate, val pages: Int)

data class MonthlyBooks(val year: Int, val month: Int, val count: Int)

data class MonthlyPages(val year: Int, val month: Int, val pages: Int)

data class YearlyBooks(val year: Int, val count: Int)

data class YearlyPages(val year: Int, val pages: Int)

data class MonthlySpend(val year: Int, val month: Int, val amount: Double)

data class YearlySpend(val year: Int, val amount: Double)

data class ReadingStats(
    val totalSessions: Int,
    val totalPages: Int,
    val totalMinutes: Long,
    val dailyPages: List<DailyPages>,
    val monthlyCompleted: List<MonthlyBooks>,
    val monthlyPages: List<MonthlyPages>,
    val monthlyPurchased: List<MonthlyBooks>,
    val monthlySpend: List<MonthlySpend>,
    val yearlyCompleted: List<YearlyBooks>,
    val yearlyPages: List<YearlyPages>,
    val yearlyPurchased: List<YearlyBooks>,
    val yearlySpend: List<YearlySpend>,
    val minYear: Int,
    val maxYear: Int,
)

object StatsCalculator {

    fun compute(
        sessions: List<ReadingSessionEntity>,
        finishedBookDates: List<Long>,
        purchasedBooks: List<BookEntity> = emptyList(),
    ): ReadingStats {
        val dailyPages = aggregateDailyPages(sessions)
        val totalPages = dailyPages.sumOf { it.pages }
        val totalMinutes = sessions.mapNotNull { it.durationSec }.sum() / 60

        val monthly = monthlyCounts(finishedBookDates)
        // Купленной считается книга с датой покупки; в сумму попадают только
        // те, у которых заполнена ещё и цена.
        val monthlyPurchased = monthlyCounts(purchasedBooks.mapNotNull { it.purchaseDate })
        val monthlySpend = monthlySums(
            purchasedBooks.mapNotNull { book ->
                val date = book.purchaseDate ?: return@mapNotNull null
                val price = book.purchasePrice ?: return@mapNotNull null
                date to price
            },
        )

        val monthlyPagesList = dailyPages
            .groupBy { YearMonth.from(it.date) }
            .map { (ym, list) -> MonthlyPages(ym.year, ym.monthValue, list.sumOf { it.pages }) }
            .sortedWith(compareBy({ it.year }, { it.month }))

        val yearlyCompleted = yearlyCounts(monthly)
        val yearlyPurchased = yearlyCounts(monthlyPurchased)
        val yearlySpend = yearlySums(monthlySpend)
        val yearlyPages = monthlyPagesList
            .groupBy { it.year }
            .map { (y, list) -> YearlyPages(y, list.sumOf { it.pages }) }
            .sortedBy { it.year }

        val sessionYears = monthlyPagesList.map { it.year }
        val finishedYears = monthly.map { it.year }
        val purchasedYears = monthlyPurchased.map { it.year }
        val allYears = sessionYears + finishedYears + purchasedYears
        val minYear = allYears.minOrNull() ?: LocalDate.now().year
        val maxYear = allYears.maxOrNull() ?: LocalDate.now().year

        return ReadingStats(
            totalSessions = sessions.size,
            totalPages = totalPages,
            totalMinutes = totalMinutes,
            dailyPages = dailyPages,
            monthlyCompleted = monthly,
            monthlyPages = monthlyPagesList,
            monthlyPurchased = monthlyPurchased,
            monthlySpend = monthlySpend,
            yearlyCompleted = yearlyCompleted,
            yearlyPages = yearlyPages,
            yearlyPurchased = yearlyPurchased,
            yearlySpend = yearlySpend,
            minYear = minYear,
            maxYear = maxYear,
        )
    }

    /** Сколько книг приходится на каждый месяц — по дате (прочтения или покупки). */
    private fun monthlyCounts(dates: List<Long>): List<MonthlyBooks> =
        dates.map { ts ->
            val date = ts.toLocalDate()
            MonthlyBooks(date.year, date.monthValue, 0)
        }.groupingBy { it.year to it.month }
            .eachCount()
            .map { (k, v) -> MonthlyBooks(k.first, k.second, v) }
            .sortedWith(compareBy({ it.year }, { it.month }))

    /** Сумма покупок по месяцам — цены книг, купленных в этом месяце. */
    private fun monthlySums(purchases: List<Pair<Long, Double>>): List<MonthlySpend> =
        purchases.groupBy { YearMonth.from(it.first.toLocalDate()) }
            .map { (ym, list) -> MonthlySpend(ym.year, ym.monthValue, list.sumOf { it.second }) }
            .sortedWith(compareBy({ it.year }, { it.month }))

    private fun yearlyCounts(monthly: List<MonthlyBooks>): List<YearlyBooks> =
        monthly
            .groupBy { it.year }
            .map { (y, list) -> YearlyBooks(y, list.sumOf { it.count }) }
            .sortedBy { it.year }

    private fun yearlySums(monthly: List<MonthlySpend>): List<YearlySpend> =
        monthly
            .groupBy { it.year }
            .map { (y, list) -> YearlySpend(y, list.sumOf { it.amount }) }
            .sortedBy { it.year }

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
        return deltaPairs(sessions).mapNotNull { (s, pages) ->
            if (pages <= 0) null
            else s.startedAt.toLocalDate() to pages
        }
    }

    /**
     * Сколько страниц прочитано в каждой сессии — разница между её end_page
     * и end_page предыдущей (по времени) сессии той же книги. Первая сессия
     * книги отсчитывается от нуля, поэтому сумма дельт = прочитано всего.
     * Сессии без корректной даты игнорируются — так же, как в статистике.
     *
     * @return id сессии -> страницы (может быть отрицательным, если страницу
     *   перечитали заново).
     */
    fun sessionDeltas(sessions: List<ReadingSessionEntity>): Map<Long, Int> =
        deltaPairs(sessions).associate { (s, pages) -> s.id to pages }

    // Сессия -> прочитано страниц с прошлой сессии этой же книги.
    // The eligible set is also the baseline set: the previous session of a
    // book is only a baseline if it counts as a reading session itself, so
    // both filters live here and every caller gets the same set.
    private fun deltaPairs(
        sessions: List<ReadingSessionEntity>,
    ): List<Pair<ReadingSessionEntity, Int>> =
        sessions
            .filter { it.endPage != null && it.startedAt > 0L }
            .groupBy { it.bookId }
            .values
            .flatMap { bookSessions ->
                // id breaks ties: two sessions can share a millisecond, and
                // without it the order between them is whatever the DAO's
                // ORDER BY started_at DESC happened to produce.
                val ordered = bookSessions.sortedWith(compareBy({ it.startedAt }, { it.id }))
                ordered.mapIndexedNotNull { i, s ->
                    val end = s.endPage ?: return@mapIndexedNotNull null
                    val prev = ordered.getOrNull(i - 1)?.endPage ?: 0
                    s to (end - prev)
                }
            }

    fun lastNMonths(now: LocalDate, n: Int): List<YearMonth> {
        val current = YearMonth.of(now.year, now.month)
        return (0 until n).map { current.minusMonths(it.toLong()) }.reversed()
    }

    fun yearRange(min: Int, max: Int): List<Int> = (min..max).toList()
}

