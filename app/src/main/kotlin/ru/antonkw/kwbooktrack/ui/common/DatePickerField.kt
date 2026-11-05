package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.antonkw.kwbooktrack.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Поле даты: значение — строка «dd.MM.yyyy», тап открывает календарь.
 * Ручной ввод запрещён (поле disabled, как фильтр периода в списке) — формат
 * всегда один, разбирать ввод не нужно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        trailingIcon = {
            Icon(Icons.Filled.CalendarToday, contentDescription = stringResource(R.string.cd_choose_date))
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier.clickable { showPicker = true },
    )
    if (showPicker) {
        val initial = remember(value) { parseDate(value) ?: LocalDate.now() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onValueChange(
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT),
                        )
                    }
                    showPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                Row {
                    // Ручного ввода нет — значит, и снять дату можно только тут.
                    if (value.isNotBlank()) {
                        TextButton(onClick = {
                            onValueChange("")
                            showPicker = false
                        }) { Text(stringResource(R.string.action_clear_date)) }
                    }
                    TextButton(onClick = { showPicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private fun parseDate(raw: String): LocalDate? =
    runCatching { LocalDate.parse(raw.trim(), DATE_FMT) }.getOrNull()
