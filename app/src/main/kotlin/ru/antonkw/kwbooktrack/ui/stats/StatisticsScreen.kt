package ru.antonkw.kwbooktrack.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.antonkw.kwbooktrack.data.stats.YearlyBooks
import ru.antonkw.kwbooktrack.data.stats.YearlyPages
import ru.antonkw.kwbooktrack.data.stats.StatsCalculator
import java.time.LocalDate
import kotlin.math.max
import ru.antonkw.kwbooktrack.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.stats_summary), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.stats_sessions, stats.totalSessions))
                    Text(stringResource(R.string.stats_pages, stats.totalPages))
                    Text(stringResource(R.string.stats_minutes, stats.totalMinutes))
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipLabel(
                    selected = true,
                    onClick = { /* metric click - no-op or future */ },
                    label = stringResource(R.string.stats_label_pages),
                    modifier = Modifier.weight(1f),
                )
                FilterChipLabel(
                    selected = true,
                    onClick = { /* metric click - no-op or future */ },
                    label = stringResource(R.string.stats_label_books),
                    modifier = Modifier.weight(1f),
                )
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    val periodLabel = periodLabel(selection)
                    Text(
                        text = when (selection.mode) {
                            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_pages_by_month, periodLabel)
                            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_pages_by_year)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    BarChartWithLabels(
                        labels = when (selection.mode) {
                            ChartMode.BY_MONTH -> monthLabels()
                            ChartMode.BY_YEAR -> StatsCalculator.yearRange(stats.minYear, stats.maxYear).map { it.toString() }
                        },
                        values = when (selection.mode) {
                            ChartMode.BY_MONTH -> monthlyChartValues(stats, selection.year)
                            ChartMode.BY_YEAR -> stats.yearlyPages.associate { it.year to it.pages }
                        },
                    )
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    val periodLabel = periodLabel(selection)
                    Text(
                        text = when (selection.mode) {
                            ChartMode.BY_MONTH -> stringResource(R.string.stats_chart_finished_by_month, periodLabel)
                            ChartMode.BY_YEAR -> stringResource(R.string.stats_chart_finished_by_year)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    BarChartWithLabels(
                        labels = when (selection.mode) {
                            ChartMode.BY_MONTH -> monthLabels()
                            ChartMode.BY_YEAR -> StatsCalculator.yearRange(stats.minYear, stats.maxYear).map { it.toString() }
                        },
                        values = when (selection.mode) {
                            ChartMode.BY_MONTH -> monthlyChartValuesBooks(stats, selection.year)
                            ChartMode.BY_YEAR -> stats.yearlyCompleted.associate { it.year to it.count }
                        },
                    )
                }
            }
        }
    }
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

private fun monthlyChartValues(
    stats: ru.antonkw.kwbooktrack.data.stats.ReadingStats,
    year: Int,
): Map<Int, Int> {
    val byMonth = stats.monthlyPages.filter { it.year == year }.associate { it.month to it.pages }
    return (1..12).associateWith { byMonth[it] ?: 0 }
}

private fun monthlyChartValuesBooks(
    stats: ru.antonkw.kwbooktrack.data.stats.ReadingStats,
    year: Int,
): Map<Int, Int> {
    val byMonth = stats.monthlyCompleted.filter { it.year == year }.associate { it.month to it.count }
    return (1..12).associateWith { byMonth[it] ?: 0 }
}

@Composable
private fun monthLabels(): List<String> = stringArrayResource(R.array.months_short).toList()

@Composable
private fun BarChartWithLabels(
    labels: List<String>,
    values: Map<Int, Int>,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barValueColor = MaterialTheme.colorScheme.onPrimary
    val maxVal = max(values.values.maxOrNull() ?: 0, 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            labels.forEachIndexed { idx, _ ->
                val v = values[idx + 1] ?: values.values.toList().getOrNull(idx) ?: 0
                val frac = if (maxVal > 0) v.toFloat() / maxVal else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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
                                    text = v.toString(),
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
                    modifier = Modifier.weight(1f),
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

@Composable
private fun androidx.compose.foundation.layout.RowScope.FilterChipLabel(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            if (trailingIcon != null) {
                Spacer(modifier = Modifier.padding(start = 6.dp))
                Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun monthShort(m: Int): String =
    stringArrayResource(R.array.months_short).getOrNull(m - 1) ?: m.toString()
