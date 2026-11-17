package ru.antonkw.kwbooktrack.data.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.time.toDayMs
import java.time.LocalDate

class MovieStatsCalculatorTest {

    @Test
    fun countsWatchedMoviesByMonth() {
        val stats = MovieStatsCalculator.compute(
            listOf(
                watched("Первый", LocalDate.of(2024, 2, 10)),
                watched("Второй", LocalDate.of(2024, 2, 20)),
                watched("Третий", LocalDate.of(2024, 3, 1)),
            ),
        )

        assertThat(stats.totalMovies).isEqualTo(3)
        assertThat(stats.monthlyWatched.map { it.month to it.count })
            .containsExactly(2 to 2, 3 to 1).inOrder()
    }

    @Test
    fun sumsMinutesByMonth() {
        // Время просмотра фильма — его продолжительность.
        val stats = MovieStatsCalculator.compute(
            listOf(
                watched("Первый", LocalDate.of(2024, 2, 10), duration = 147),
                watched("Второй", LocalDate.of(2024, 2, 20), duration = 90),
            ),
        )

        assertThat(stats.totalMinutes).isEqualTo(237)
        assertThat(stats.monthlyMinutes.single().pages).isEqualTo(237)
    }

    @Test
    fun ignoresMoviesWithoutWatchedDate() {
        // Фильм без даты просмотра не смотрели — в статистику он не попадает.
        val stats = MovieStatsCalculator.compute(
            listOf(
                watched("Посмотрен", LocalDate.of(2024, 2, 10)),
                watched("Не посмотрен", null),
            ),
        )

        assertThat(stats.totalMovies).isEqualTo(1)
        assertThat(stats.monthlyWatched).hasSize(1)
    }

    @Test
    fun movieWithoutDurationCountsAsZeroMinutes() {
        val stats = MovieStatsCalculator.compute(
            listOf(watched("Без хронометража", LocalDate.of(2024, 2, 10), duration = null)),
        )

        assertThat(stats.totalMovies).isEqualTo(1)
        assertThat(stats.totalMinutes).isEqualTo(0)
    }

    @Test
    fun computesYearlyAggregates() {
        val stats = MovieStatsCalculator.compute(
            listOf(
                watched("Первый", LocalDate.of(2024, 2, 10), duration = 100),
                watched("Второй", LocalDate.of(2025, 2, 10), duration = 200),
            ),
        )

        assertThat(stats.yearlyWatched.map { it.year to it.count })
            .containsExactly(2024 to 1, 2025 to 1).inOrder()
        assertThat(stats.yearlyMinutes.map { it.year to it.pages })
            .containsExactly(2024 to 100, 2025 to 200).inOrder()
        assertThat(stats.minYear).isEqualTo(2024)
        assertThat(stats.maxYear).isEqualTo(2025)
    }

    private fun watched(title: String, date: LocalDate?, duration: Int? = 120) = MovieEntity(
        title = title,
        year = date?.year,
        durationMin = duration,
        watchedAt = date?.toDayMs(),
        createdAt = 0L,
        updatedAt = 0L,
    )
}
