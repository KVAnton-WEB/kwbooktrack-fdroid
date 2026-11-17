package ru.antonkw.kwbooktrack.data.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity

/**
 * Статистика фильмов с несколькими датами просмотра.
 *
 * Просмотр — одна дата: пересмотренный фильм даёт два просмотра и вдвое
 * больше минут.
 */
class MovieStatsMultiDateTest {

    @Test
    fun twoWatchDatesCountAsTwoViewings() {
        val movie = movie(durationMin = 120)
        val stats = MovieStatsCalculator.compute(
            listOf(movie),
            mapOf(movie.id to listOf(1_000L, 2_000L)),
        )

        assertThat(stats.totalMovies).isEqualTo(2)
        assertThat(stats.totalMinutes).isEqualTo(240)
    }

    @Test
    fun oneWatchDateCountsAsOneViewing() {
        val movie = movie(durationMin = 120)
        val stats = MovieStatsCalculator.compute(
            listOf(movie),
            mapOf(movie.id to listOf(1_000L)),
        )

        assertThat(stats.totalMovies).isEqualTo(1)
        assertThat(stats.totalMinutes).isEqualTo(120)
    }

    @Test
    fun movieWithoutDatesIsNotCounted() {
        val movie = movie(durationMin = 120)
        val stats = MovieStatsCalculator.compute(listOf(movie), emptyMap())

        assertThat(stats.totalMovies).isEqualTo(0)
        assertThat(stats.totalMinutes).isEqualTo(0)
    }

    private fun movie(durationMin: Int) = MovieEntity(
        title = "Малхолланд Драйв",
        durationMin = durationMin,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
