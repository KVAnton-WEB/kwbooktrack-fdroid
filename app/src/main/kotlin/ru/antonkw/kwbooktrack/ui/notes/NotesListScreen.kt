package ru.antonkw.kwbooktrack.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.antonkw.kwbooktrack.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.currentQuery.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notes_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.notes_search_hint)) },
                singleLine = true,
            )

            when (val s = state) {
                NotesListUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                is NotesListUiState.Loaded -> {
                    if (s.items.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (query.isBlank())
                                    stringResource(R.string.empty_notes_detailed)
                                else stringResource(R.string.empty_search, query),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    } else {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(modifier = Modifier.fillMaxSize()) {
val scrollbarWidth = 8.dp
                            // Touch zone is 3× the visual width — wider than
                            // the visual 8dp thumb so the user can actually
                            // hit it with a finger, but kept small enough that
                            // the gap between the bar and the content stays
                            // tight (24dp − 8dp = 16dp).
                            val scrollbarTouchWidth = 24.dp
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                // padding(end = 16.dp) leaves an 8dp gap to the
                                // visual 8dp scrollbar thumb; the remaining
                                // 8dp lane inside the scrollbar's 24dp touch
                                // zone is invisible to LazyColumn (the
                                // scrollbar sibling wins hit-test there).
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(end = 16.dp),
                            ) {
                                items(s.items, key = { it.note.id }) { item ->
                                    NoteListRow(
                                        item = item,
                                        onClick = { onOpenNote(item.note.id) },
                                    )
                                }
                            }
                            ru.antonkw.kwbooktrack.ui.common.VerticalListScrollbar(
                                listState = listState,
                                modifier = Modifier.align(Alignment.CenterEnd),
                                width = scrollbarWidth,
                                touchWidth = scrollbarTouchWidth,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteListRow(item: NoteListItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (item.note.kind == ru.antonkw.kwbooktrack.data.db.entity.NoteKind.QUOTE)
                    Icons.Filled.FormatQuote else Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp).padding(top = 2.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = item.bookTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.author.isNotBlank()) {
                    Text(
                        text = item.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.note.bodyMd,
                    style = if (item.note.kind == ru.antonkw.kwbooktrack.data.db.entity.NoteKind.QUOTE)
                        MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                    else MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                item.note.page?.let {
                    Text(
                        text = stringResource(R.string.session_end_page, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
