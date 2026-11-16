package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.antonkw.kwbooktrack.R

/**
 * Выпадающий список со значениями — тот же, что у фильтра «Тип» и у
 * переключателя «Книги ⇄ Фильмы».
 *
 * Индекс выбранного варианта приходит наружу: список вариантов и список
 * значений — одно и то же, и порядок enum'а задаёт порядок в меню.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDropdown(
    label: String,
    selectedLabel: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEachIndexed { idx, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelected(idx); expanded = false },
                )
            }
        }
    }
}

/**
 * Подпись периода: «Всё время», «2024» или «февраль 2024».
 *
 * Для месяца ресурс ждёт два аргумента — год и название месяца, — а
 * [PeriodOption.formatArg] хранит их одной строкой через «|».
 */
@Composable
fun periodText(option: PeriodOption): String {
    val arg = option.formatArg ?: return stringResource(option.labelRes)
    return when (option.labelRes) {
        R.string.period_year -> stringResource(option.labelRes, arg)
        R.string.period_year_month -> {
            val parts = arg.split("|", limit = 2)
            val year = parts.getOrNull(0)?.toIntOrNull()
            val monthRes = parts.getOrNull(1)?.toIntOrNull()
            if (year != null && monthRes != null) {
                stringResource(option.labelRes, year, stringResource(monthRes))
            } else {
                stringResource(option.labelRes, arg)
            }
        }
        else -> stringResource(option.labelRes, arg)
    }
}
