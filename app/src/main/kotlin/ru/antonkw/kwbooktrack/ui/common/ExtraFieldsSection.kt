package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

/**
 * Чужие поля Tellico («livelib/fantlab», «чтец», «иллюстратор»…) — на карточке.
 *
 * Значение-ссылка кликабельно: открывает браузер.
 */
@Composable
fun ExtraFieldsSection(
    extraFields: Map<String, ExtraField>,
    modifier: Modifier = Modifier,
) {
    val rows = remember(extraFields) {
        extraFields.values.filter { it.value.isNotBlank() }.sortedBy { it.title }
    }
    if (rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.label_extra_fields),
            style = MaterialTheme.typography.titleMedium,
        )
        rows.forEach { extra ->
            Column {
                Text(
                    text = extra.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val url = asUrl(extra.value)
                if (url != null) {
                    UrlText(url)
                } else {
                    Text(text = extra.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
