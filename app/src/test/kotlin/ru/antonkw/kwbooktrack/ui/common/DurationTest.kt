package ru.antonkw.kwbooktrack.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Разложение длительности фильма на часы и минуты.
 *
 * 132 минуты — это 2 ч 12 мин, а не «132»: в списке, карточке и статистике
 * длительность показывается человекочитаемо.
 */
class DurationTest {

    @Test
    fun lessThanAnHourStaysInMinutes() {
        val parts = splitDuration(45)

        assertThat(parts.kind).isEqualTo(DurationKind.MINUTES_ONLY)
        assertThat(parts.hours).isEqualTo(0)
        assertThat(parts.minutes).isEqualTo(45)
    }

    @Test
    fun wholeHoursHaveNoMinutes() {
        val parts = splitDuration(120)

        assertThat(parts.kind).isEqualTo(DurationKind.HOURS_ONLY)
        assertThat(parts.hours).isEqualTo(2)
        assertThat(parts.minutes).isEqualTo(0)
    }

    @Test
    fun hoursAndMinutesAreSplit() {
        val parts = splitDuration(132)

        assertThat(parts.kind).isEqualTo(DurationKind.HOURS_AND_MINUTES)
        assertThat(parts.hours).isEqualTo(2)
        assertThat(parts.minutes).isEqualTo(12)
    }

    @Test
    fun zeroMinutesStaysInMinutes() {
        val parts = splitDuration(0)

        assertThat(parts.kind).isEqualTo(DurationKind.MINUTES_ONLY)
        assertThat(parts.minutes).isEqualTo(0)
    }

    @Test
    fun negativeDurationDoesNotProduceNegativeMinutes() {
        // Данные из импорта могут быть кривыми: минус в интерфейсе не показываем.
        val parts = splitDuration(-5)

        assertThat(parts.kind).isEqualTo(DurationKind.MINUTES_ONLY)
        assertThat(parts.hours).isEqualTo(0)
        assertThat(parts.minutes).isEqualTo(0)
    }
}
