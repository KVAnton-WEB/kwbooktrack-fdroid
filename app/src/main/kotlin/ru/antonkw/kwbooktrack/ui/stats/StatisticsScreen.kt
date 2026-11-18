package ru.antonkw.kwbooktrack.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.antonkw.kwbooktrack.data.stats.MonthlyBooks
import ru.antonkw.kwbooktrack.data.stats.MonthlyPages
import ru.antonkw.kwbooktrack.data.stats.MovieStats
import ru.antonkw.kwbooktrack.data.stats.ReadingStats
import ru.antonkw.kwbooktrack.data.stats.YearlyPages
import ru.antonkw.kwbooktrack.ui.common.formatDuration
import ru.antonkw.kwbooktrack.ui.common.formatDurationDot
import kotlin.math.max
import ru.antonkw.kwbooktrack.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val data by viewModel.stats.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState0())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (val d = data) {
                        is StatisticsData.Books -> {
                            Text(stringResource(R.string.stats_sessions, d.stats.totalSessions))
                            Text(stringResource(R.string.stats_pages, d.stats.totalPages))
                        }
                        is StatisticsData.Movies -> {
                            Text(stringResource(R.string.stats_movies_watched, d.stats.totalMovies))
                            Text(stringResource(R.string.stats_minutes_watched, formatDuration(d.stats.totalMinutes)))
                        }
                    }
                }
            }

            PeriodSelector(
                year = selection.year,
                onStepYear = { viewModel.stepYear(it) },
                onReset = { viewModel.resetPeriod() },
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selection.mode == ChartMode.BY_MONTH,
                    onClick = { viewModel.setMode(ChartMode.BY_MONTH) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.stats_tab_months)) }
                SegmentedButton(
                    selected = selection.mode == ChartMode.BY_YEAR,
                    onClick = { viewModel.setMode(ChartMode.BY_YEAR) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.stats_tab_years)) }
            }

            when (val d = data) {
                is StatisticsData.Books -> BookCharts(d.stats, selection)
                is StatisticsData.Movies -> MovieCharts(d.stats, selection)
            }
        }
    }
}

/** Графики книг: прочитано, страниц, куплено, потрачено. */
@Composable
private fun BookCharts(stats: ReadingStats, selection: ChartSelection) {
    val periodLabel = periodLabel(selection)
    val chartLabels = when (selection.mode) {
        ChartMode.BY_MONTH -> monthLabels()
        // Только годы с данными: ряд 2010..2014 без единой книги давал бы
        // четыре пустых столбика и растягивал график.
        ChartMode.BY_YEAR -> yearsWithData(
            stats.yearlyCompleted.map { it.year },
            stats.yearlyPages.map { it.year },
            stats.yearlyPurchased.map { it.year },
            stats.yearlySpend.map { it.year },
        ).ifEmpty { listOf(selection.year) }.map { it.toString() }
    }

    ChartCard(
        title = when (selection.mode) {
            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_finished_by_month, periodLabel)
            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_finished_by_year)
        },
        labels = chartLabels,
        scrollable = selection.mode == ChartMode.BY_YEAR,
        values = when (selection.mode) {
            ChartMode.BY_MONTH -> monthlyCountsChartValues(stats.monthlyCompleted, selection.year)
            ChartMode.BY_YEAR -> stats.yearlyCompleted.associate { it.year to it.count }
        },
    )

    ChartCard(
        title = when (selection.mode) {
            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_pages_by_month, periodLabel)
            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_pages_by_year)
        },
        labels = chartLabels,
        scrollable = selection.mode == ChartMode.BY_YEAR,
        values = when (selection.mode) {
            ChartMode.BY_MONTH -> chartPagesByMonth(stats.monthlyPages, selection.year)
            ChartMode.BY_YEAR -> chartPagesByYear(stats.yearlyPages)
        },
    )

    ChartCard(
        title = when (selection.mode) {
            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_purchased_by_month, periodLabel)
            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_purchased_by_year)
        },
        labels = chartLabels,
        scrollable = selection.mode == ChartMode.BY_YEAR,
        values = when (selection.mode) {
            ChartMode.BY_MONTH -> monthlyCountsChartValues(stats.monthlyPurchased, selection.year)
            ChartMode.BY_YEAR -> stats.yearlyPurchased.associate { it.year to it.count }
        },
    )

    ChartCard(
        title = when (selection.mode) {
            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_spent_by_month, periodLabel)
            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_spent_by_year)
        },
        labels = chartLabels,
        scrollable = selection.mode == ChartMode.BY_YEAR,
        values = when (selection.mode) {
            ChartMode.BY_MONTH -> monthlySpendChartValues(stats, selection.year)
            ChartMode.BY_YEAR -> stats.yearlySpend.associate { it.year to it.amount }
        },
    )
}

/**
 * Графики фильмов: просмотрено фильмов и минут просмотра.
 *
 * Графиков «куплено» и «потрачено» у фильмов нет: в коллекции Tellico для
 * видео есть цена, но нет даты покупки — агрегировать нечего.
 */
@Composable
private fun MovieCharts(stats: MovieStats, selection: ChartSelection) {
    val periodLabel = periodLabel(selection)
    val chartLabels = when (selection.mode) {
        ChartMode.BY_MONTH -> monthLabels()
        ChartMode.BY_YEAR -> yearsWithData(
            stats.yearlyWatched.map { it.year },
            stats.yearlyMinutes.map { it.year },
        ).ifEmpty { listOf(selection.year) }.map { it.toString() }
    }

    ChartCard(
        title = when (selection.mode) {
            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_movies_by_month, periodLabel)
            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_movies_by_year)
        },
        labels = chartLabels,
        scrollable = selection.mode == ChartMode.BY_YEAR,
        values = when (selection.mode) {
            ChartMode.BY_MONTH -> monthlyCountsChartValues(stats.monthlyWatched, selection.year)
            ChartMode.BY_YEAR -> stats.yearlyWatched.associate { it.year to it.count }
        },
    )

    ChartCard(
        title = when (selection.mode) {
            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_minutes_by_month, periodLabel)
            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_minutes_by_year)
        },
        labels = chartLabels,
        scrollable = selection.mode == ChartMode.BY_YEAR,
        values = when (selection.mode) {
            ChartMode.BY_MONTH -> monthlyMinutesChartValues(stats.monthlyMinutes, selection.year)
            ChartMode.BY_YEAR -> stats.yearlyMinutes.associate { it.year to it.pages }
        },
        // Минуты в подписи столбика не читаются: 285 → «4.45».
        format = ::formatDurationDot,
    )
}

@Composable
private fun ChartCard(
    title: String,
    labels: List<String>,
    values: Map<Int, Int>,
    format: (Int) -> String = { it.toString() },
    /** Прокрутка — только для годов: месяцев всегда 12, они влезают. */
    scrollable: Boolean = false,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            BarChartWithLabels(labels = labels, values = values, format = format, scrollable = scrollable)
        }
    }
}

/** Минуты просмотра по месяцам выбранного года. */
private fun monthlyMinutesChartValues(
    monthly: List<MonthlyPages>,
    year: Int,
): Map<Int, Int> = (1..12).associateWith { month ->
    monthly.firstOrNull { it.year == year && it.month == month }?.pages ?: 0
}

@Composable
private fun periodLabel(selection: ChartSelection): String =
    if (selection.labelArgs.isEmpty()) stringResource(selection.labelRes())
    else stringResource(selection.labelRes(), *selection.labelArgs.toTypedArray())

@Composable
private fun PeriodSelector(
    year: Int,
    onStepYear: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onStepYear(-1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            }
            Text(
                text = year.toString(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = { onStepYear(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        TextButton(
            onClick = onReset,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.action_reset))
        }
    }
}

@Composable
private fun rememberScrollState0() = androidx.compose.foundation.rememberScrollState()

/**
 * Страниц по месяцам — для графика.
 *
 * Отрицательным месяц быть не может: дельта закрепляется за днём поздней
 * сессии, и при переходе границы месяца месяц получал отрицательное число
 * прочитанных страниц.
 */
internal fun chartPagesByMonth(monthly: List<MonthlyPages>, year: Int): Map<Int, Int> {
    val byMonth = monthly.filter { it.year == year }.associate { it.month to it.pages }
    return (1..12).associateWith { (byMonth[it] ?: 0).coerceAtLeast(0) }
}

/** Страниц по годам — для графика. */
internal fun chartPagesByYear(yearly: List<YearlyPages>): Map<Int, Int> =
    yearly.associate { it.year to it.pages.coerceAtLeast(0) }

/**
 * Годы для графика — только те, где есть данные.
 *
 * Ряд «от первого года до последнего» рисовал пустые столбики за годы, в
 * которые ничего не происходило: в коллекции 2011 и 2026 между ними получалось
 * 15 столбиков, из которых два с половиной. Наборы годов разных графиков
 * объединяются — все графики должны говорить про одни и те же годы.
 */
internal fun yearsWithData(vararg years: List<Int>): List<Int> =
    years.flatMap { it }.filter { it > 0 }.distinct().sorted()

/** Сколько книг (прочитанных или купленных) приходится на каждый месяц года. */
private fun monthlyCountsChartValues(monthly: List<MonthlyBooks>, year: Int): Map<Int, Int> {
    val byMonth = monthly.filter { it.year == year }.associate { it.month to it.count }
    return (1..12).associateWith { byMonth[it] ?: 0 }
}

/** Сумма покупок по месяцам года — в целых единицах, копейки округляются. */
private fun monthlySpendChartValues(stats: ReadingStats, year: Int): Map<Int, Int> {
    val byMonth = stats.monthlySpend.filter { it.year == year }
        .associate { it.month to it.amount }
    return (1..12).associateWith { byMonth[it] ?: 0 }
}

@Composable
private fun monthLabels(): List<String> = stringArrayResource(R.array.months_short).toList()

@Composable
private fun BarChartWithLabels(
    labels: List<String>,
    values: Map<Int, Int>,
    format: (Int) -> String = { it.toString() },
    scrollable: Boolean,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barValueColor = MaterialTheme.colorScheme.onPrimary
    val maxVal = max(values.values.maxOrNull() ?: 0, 1)
    // Прокрутка — только для годов, и только когда их больше, чем влезает.
    // Месяцев всегда 12: они делят ширину поровну, и трогать их незачем.
    val scroll = scrollable && labels.size > MAX_VISIBLE_BARS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            labels.forEachIndexed { idx, label ->
                // BY_MONTH labels are short month names ("Янв"…) → fall back to idx+1.
                // BY_YEAR labels are 4-digit years → use the year as the map key.
                val key = label.toIntOrNull() ?: (idx + 1)
                val v = values[key] ?: 0
                val frac = if (maxVal > 0) v.toFloat() / maxVal else 0f
                Box(
                    modifier = barModifier(scroll),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((150 * frac).dp.coerceAtLeast(if (v > 0) 8.dp else 0.dp))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (v > 0) barColor else emptyColor),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (v > 0) {
                                Text(
                                    text = format(v),
                                    color = barValueColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = barModifier(scroll),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Ширина столбца: делим ширину экрана, в скролле — фиксированная. */
private fun RowScope.barModifier(scroll: Boolean): Modifier =
    if (scroll) Modifier.width(BAR_WIDTH) else Modifier.weight(1f)

/** Сколько столбцов видно без прокрутки. */
private const val MAX_VISIBLE_BARS = 6

/** Ширина столбца в прокручиваемом ряду: шесть таких влезают в экран. */
private val BAR_WIDTH = 52.dp
