package ru.antonkw.kwbooktrack.data.stats

import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.time.YearMonth

/**
 * Статистика фильмов — по времени просмотра.
 *
 * Время просмотра фильма — его продолжительность (`running-time`): другого
 * источника длительности нет. Фильм без продолжительности даёт ноль минут, но
 * в счётчик просмотренных входит.
 */
data class MovieStats(
    val totalMovies: Int = 0,
    val totalMinutes: Int = 0,
    val monthlyWatched: List<MonthlyBooks> = emptyList(),
    val monthlyMinutes: List<MonthlyPages> = emptyList(),
    val yearlyWatched: List<YearlyBooks> = emptyList(),
    val yearlyMinutes: List<YearlyPages> = emptyList(),
    val minYear: Int = 0,
    val maxYear: Int = 0,
)

/**
 * Считает агрегаты по фильмам — по образцу [StatsCalculator], но величины
 * другие: фильмов и минут, а не книг и страниц.
 */
object MovieStatsCalculator {

    /** Фильмы без даты просмотра в статистику не попадают: их не смотрели. */
    fun compute(movies: List<MovieEntity>): MovieStats {
        val watched = movies.filter { it.watchedAt != null }
        if (watched.isEmpty()) return MovieStats()

        val monthlyWatched = watched
            .groupBy { YearMonth.from(it.watchedAt!!.toLocalDate()) }
            .map { (month, items) -> MonthlyBooks(month.year, month.monthValue, items.size) }
            .sortedWith(compareBy({ it.year }, { it.month }))
        val monthlyMinutes = watched
            .groupBy { YearMonth.from(it.watchedAt!!.toLocalDate()) }
            .map { (month, items) ->
                MonthlyPages(month.year, month.monthValue, items.sumOf { it.durationMin ?: 0 })
            }
            .sortedWith(compareBy({ it.year }, { it.month }))
        val yearlyWatched = monthlyWatched
            .groupBy { it.year }
            .map { (year, items) -> YearlyBooks(year, items.sumOf { it.count }) }
            .sortedBy { it.year }
        val yearlyMinutes = monthlyMinutes
            .groupBy { it.year }
            .map { (year, items) -> YearlyPages(year, items.sumOf { it.pages }) }
            .sortedBy { it.year }

        val years = watched.map { it.watchedAt!!.toLocalDate().year }
        return MovieStats(
            totalMovies = watched.size,
            totalMinutes = watched.sumOf { it.durationMin ?: 0 },
            monthlyWatched = monthlyWatched,
            monthlyMinutes = monthlyMinutes,
            yearlyWatched = yearlyWatched,
            yearlyMinutes = yearlyMinutes,
            minYear = years.min(),
            maxYear = years.max(),
        )
    }
}
