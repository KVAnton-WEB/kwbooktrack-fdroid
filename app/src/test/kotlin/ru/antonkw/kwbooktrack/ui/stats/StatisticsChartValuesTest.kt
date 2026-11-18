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
    fun yearsWithoutDataAreSkipped() {
        // 2011 и 2026 в коллекции, между ними ничего: пятнадцати столбиков
        // пустоты быть не должно.
        val years = yearsWithData(listOf(2011, 2026), listOf(2026), emptyList())
        assertThat(years).containsExactly(2011, 2026).inOrder()
    }

    @Test
    fun yearsOfAllChartsAreMerged() {
        // У книг годы прочтения и покупок разные — графики должны говорить
        // про один и тот же набор годов.
        val years = yearsWithData(listOf(2020), listOf(2024), listOf(2022), listOf(2024))
        assertThat(years).containsExactly(2020, 2022, 2024).inOrder()
    }

    @Test
    fun noYearsAtAllGivesEmptyList() {
        assertThat(yearsWithData(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun otherYearsAreNotMixedIn() {
        val values = chartPagesByMonth(listOf(MonthlyPages(2025, 3, 50)), 2026)
        assertThat(values[3]).isEqualTo(0)
    }
}
