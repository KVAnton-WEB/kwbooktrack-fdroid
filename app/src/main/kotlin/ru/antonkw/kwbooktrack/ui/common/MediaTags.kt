package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.antonkw.kwbooktrack.R

/**
 * Секция тегов владельца — книги или фильма.
 *
 * Теги лежат в одной таблице, различается только владелец, поэтому секция
 * общая для обоих экранов. Теги правятся здесь же: чип с крестиком убирает
 * тег, поле добавления подсказывает уже существующие — как поле переплёта в
 * форме книги.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaTagsSection(
    tags: List<String>,
    suggestions: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val trimmed = input.trim()
    // Подсказки — только пока поле в фокусе и ввод не пуст: иначе список
    // тегов висит на экране и закрывает содержимое карточки.
    val filtered = remember(isFocused, trimmed, suggestions) {
        if (!isFocused || trimmed.isBlank()) {
            emptyList()
        } else {
            val q = trimmed.lowercase()
            suggestions.filter { it.lowercase().contains(q) && it !in tags }.take(6)
        }
    }
    val add = {
        if (trimmed.isNotEmpty()) {
            onAddTag(trimmed)
            input = ""
        }
    }

    Text(stringResource(R.string.label_tags), style = MaterialTheme.typography.titleMedium)
    if (tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                Row(
                    modifier = Modifier
                        .clickable { onRemoveTag(tag) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_remove_tag, tag),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp).size(14.dp),
                    )
                }
            }
        }
    }
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(stringResource(R.string.label_add_tag)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (trimmed.isNotEmpty()) {
                    IconButton(onClick = add) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_add_tag))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
        )
        if (filtered.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Column {
                    filtered.forEach { suggestion ->
                        Text(
                            text = suggestion,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAddTag(suggestion)
                                    input = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}