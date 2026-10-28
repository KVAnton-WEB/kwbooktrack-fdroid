package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import ru.antonkw.kwbooktrack.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthYearPickerDialog(
    initial: YearMonth,
    min: YearMonth? = null,
    max: YearMonth? = null,
    initialWholeYear: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
    onConfirmYear: ((Int) -> Unit)? = null,
) {
    val months = stringArrayResource(R.array.months_long)
    // Режим «весь год» появляется только если вызывающая сторона умеет его принять.
    val canPickYear = onConfirmYear != null
    val wholeYearLabel = stringResource(R.string.period_whole_year)
    var selectedYear by remember { mutableStateOf(initial.year) }
    var selectedMonth by remember { mutableStateOf(initial.monthValue) }
    var wholeYear by remember { mutableStateOf(initialWholeYear && canPickYear) }
    var yearExpanded by remember { mutableStateOf(false) }
    var monthExpanded by remember { mutableStateOf(false) }

    val yearRange = remember(min, max) {
        val lo = min?.year ?: (LocalDate.now().year - 5)
        val hi = max?.year ?: LocalDate.now().year
        (lo..hi).toList()
    }
    val yearEnabled = yearRange.size > 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (canPickYear) R.string.dialog_choose_period else R.string.dialog_choose_month,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = monthExpanded,
                            onExpandedChange = { monthExpanded = !monthExpanded },
                        ) {
                            OutlinedTextField(
                                value = if (wholeYear) wholeYearLabel else months[selectedMonth - 1],
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.label_month)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(monthExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                singleLine = true,
                            )
                            DropdownMenu(
                                expanded = monthExpanded,
                                onDismissRequest = { monthExpanded = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (canPickYear) {
                                    DropdownMenuItem(
                                        text = { Text(wholeYearLabel) },
                                        onClick = {
                                            wholeYear = true
                                            monthExpanded = false
                                        },
                                    )
                                }
                                months.forEachIndexed { idx, name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            selectedMonth = idx + 1
                                            wholeYear = false
                                            monthExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = yearExpanded && yearEnabled,
                            onExpandedChange = { if (yearEnabled) yearExpanded = !yearExpanded },
                        ) {
                            OutlinedTextField(
                                value = selectedYear.toString(),
                                onValueChange = {},
                                readOnly = true,
                                enabled = yearEnabled,
                                label = { Text(stringResource(R.string.label_year)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(yearExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                singleLine = true,
                            )
                            DropdownMenu(
                                expanded = yearExpanded && yearEnabled,
                                onDismissRequest = { yearExpanded = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                yearRange.forEach { y ->
                                    DropdownMenuItem(
                                        text = { Text(y.toString()) },
                                        onClick = {
                                            selectedYear = y
                                            yearExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (!wholeYear) {
                    MonthGrid(
                        year = selectedYear,
                        selectedMonth = selectedMonth,
                        onMonthClick = { selectedMonth = it },
                        max = max,
                        min = min,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (wholeYear) onConfirmYear?.invoke(selectedYear)
                else onConfirm(YearMonth.of(selectedYear, selectedMonth))
            }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun MonthGrid(
    year: Int,
    selectedMonth: Int,
    onMonthClick: (Int) -> Unit,
    min: YearMonth? = null,
    max: YearMonth? = null,
) {
    val months = stringArrayResource(R.array.months_short)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0 until 4) {
                    val monthIndex = row * 4 + col + 1
                    val ym = YearMonth.of(year, monthIndex)
                    val enabled = (min == null || ym >= min) && (max == null || ym <= max)
                    val selected = monthIndex == selectedMonth
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onMonthClick(monthIndex) }
                            .padding(vertical = 8.dp)
                            .let {
                                if (selected) {
                                    it.background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(8.dp),
                                    )
                                } else it
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = months[monthIndex - 1].take(3),
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                selected -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
