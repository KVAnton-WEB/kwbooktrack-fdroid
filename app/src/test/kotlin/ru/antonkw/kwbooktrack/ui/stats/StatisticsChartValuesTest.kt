package ru.antonkw.kwbooktrack.ui.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.stats.MonthlyPages
import ru.antonkw.kwbooktrack.data.stats.YearlyPages

/**
 * Значения графика «страниц»: месяц не может показывать отрицательное число
 * прочитанных страниц.
 */
class StatisticsChartValuesTest {

    @Test
    fun negativeMonthIsShownAsZero() {
        // Дельта закрепляется за днём поздней сессии: при переходе границы
        // месяца месяц мог получить отрицательное значение.
        val monthly = listOf(MonthlyPages(2026, 10, -20), MonthlyPages(2026, 9, 100))
        val values = chartPagesByMonth(monthly, 2026)
        assertThat(values[10]).isEqualTo(0)
        assertThat(values[9]).isEqualTo(100)
    }

    @Test
    fun negativeYearIsShownAsZero() {
        assertThat(chartPagesByYear(listOf(YearlyPages(2026, -5)))[2026]).isEqualTo(0)
    }

    @Test
    fun monthsWithoutDataAreZero() {
        val values = chartPagesByMonth(listOf(MonthlyPages(2026, 3, 50)), 2026)
        assertThat(values).hasSize(12)
        assertThat(values[1]).isEqualTo(0)
        assertThat(values[3]).isEqualTo(50)
    }

    @Test
    fun otherYearsAreNotMixedIn() {
        val values = chartPagesByMonth(listOf(MonthlyPages(2025, 3, 50)), 2026)
        assertThat(values[3]).isEqualTo(0)
    }
}
