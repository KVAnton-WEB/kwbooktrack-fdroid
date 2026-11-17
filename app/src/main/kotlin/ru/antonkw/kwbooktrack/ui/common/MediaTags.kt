package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.antonkw.kwbooktrack.R

/**
 * Секция тегов владельца — книга или фильм.
 *
 * Теги лежат в одной таблице, различается только владелец, поэтому секция
 * общая для обоих экранов.
 */
@Composable
fun MediaTagsSection(tags: List<String>) {
    if (tags.isEmpty()) return
    Text(stringResource(R.string.label_tags), style = MaterialTheme.typography.titleMedium)
    Text(
        text = tags.joinToString(", "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
