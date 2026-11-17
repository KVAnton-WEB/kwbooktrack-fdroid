package ru.antonkw.kwbooktrack.ui.common

import androidx.annotation.StringRes
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.time.toDayMs
import java.time.LocalDate
import java.time.YearMonth

/**
 * Период списка: месяц, год или «всё время».
 *
 * Общий для книг и фильмов — у книги период по датам чтения и покупки, у
 * фильма по дате просмотра, а сам диапазон один и тот же.
 */
data class PeriodOption(
    @StringRes val labelRes: Int,
    val formatArg: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    /** Период покрывает целый год, а не один месяц. */
    val wholeYear: Boolean = false,
) {
    /**
     * Дата попадает в период. Запись без этой даты не подходит ни под один
     * период, включая «Всё время» — дата нужна, чтобы сказать «эта запись».
     */
    fun matches(epochMs: Long?): Boolean {
        if (epochMs == null) return false
        if (startMillis == null || endMillis == null) return true
        return epochMs in startMillis until endMillis
    }

    /**
     * Запись подходит под период, если в диапазон попала любая из её дат.
     * Записи без дат в период не попадают — как и в [matches].
     */
    fun matchesAny(vararg dates: Long?): Boolean {
        val start = startMillis ?: return true
        val end = endMillis ?: return true
        return dates.any { it != null && it in start until end }
    }

    val isAllTime: Boolean get() = startMillis == null

    companion object {
        fun allTime() = PeriodOption(labelRes = R.string.period_all)

        fun year(year: Int): PeriodOption {
            val start = LocalDate.of(year, 1, 1).toDayMs()
            val end = LocalDate.of(year + 1, 1, 1).toDayMs()
            return PeriodOption(
                labelRes = R.string.period_year,
                formatArg = year.toString(),
                startMillis = start,
                endMillis = end,
                wholeYear = true,
            )
        }

        fun month(year: Int, month: Int, @StringRes monthNameRes: Int): PeriodOption {
            val start = LocalDate.of(year, month, 1).toDayMs()
            val end = YearMonth.of(year, month).plusMonths(1).atDay(1).toDayMs()
            return PeriodOption(
                labelRes = R.string.period_year_month,
                formatArg = "$year|$monthNameRes",
                startMillis = start,
                endMillis = end,
            )
        }
    }
}

/** Ресурсы названий месяцев — для подписи периода. */
object PeriodOptions {
    @StringRes
    private val monthResIds = listOf(
        R.string.month_jan, R.string.month_feb, R.string.month_mar,
        R.string.month_apr, R.string.month_may, R.string.month_jun,
        R.string.month_jul, R.string.month_aug, R.string.month_sep,
        R.string.month_oct, R.string.month_nov, R.string.month_dec,
    )

    @StringRes
    fun monthRes(m: Int): Int = monthResIds[m - 1]
}
