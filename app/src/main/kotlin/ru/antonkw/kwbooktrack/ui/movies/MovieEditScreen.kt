package ru.antonkw.kwbooktrack.ui.movies

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.ui.common.DatePickerField
import java.io.File

/**
 * Создание и редактирование фильма.
 *
 * Форма — как у книги, но полей фильма: продолжительность вместо числа
 * страниц, дата просмотра вместо даты прочтения, режиссёр и актёры вместо
 * автора.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieEditScreen(
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MovieEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.attachCover(uri) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEditing) R.string.movie_edit_title else R.string.movie_add_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverPreview(
                coverPath = state.coverPath,
                onPick = {
                    coverPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onClear = viewModel::clearCover,
            )

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text(stringResource(R.string.movie_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.originalTitle,
                onValueChange = viewModel::setOriginalTitle,
                label = { Text(stringResource(R.string.movie_field_original_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.year,
                onValueChange = viewModel::setYear,
                label = { Text(stringResource(R.string.movie_field_year)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.genre,
                onValueChange = viewModel::setGenre,
                label = { Text(stringResource(R.string.movie_field_genre)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.nationality,
                onValueChange = viewModel::setNationality,
                label = { Text(stringResource(R.string.movie_field_nationality)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.director,
                onValueChange = viewModel::setDirector,
                label = { Text(stringResource(R.string.movie_field_director)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.cast,
                onValueChange = viewModel::setCast,
                label = { Text(stringResource(R.string.movie_field_cast)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.duration,
                onValueChange = viewModel::setDuration,
                label = { Text(stringResource(R.string.movie_field_duration_min)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DatePickerField(
                value = state.watchedDate,
                onValueChange = viewModel::setWatchedDate,
                label = stringResource(R.string.movie_field_watched_at),
                modifier = Modifier.fillMaxWidth(),
            )
            RatingPicker(
                rating = state.rating,
                onRating = viewModel::setRating,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.movie_field_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.plot,
                onValueChange = viewModel::setPlot,
                label = { Text(stringResource(R.string.movie_field_plot)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.isDownloaded,
                    onCheckedChange = viewModel::setDownloaded,
                )
                Text(stringResource(R.string.movie_field_downloaded))
            }
        }
    }
}

/** Обложка: превью, выбор из галереи и сброс. */
@Composable
private fun CoverPreview(
    coverPath: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 144.dp)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = stringResource(R.string.label_cover),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Column {
            TextButton(onClick = onPick) { Text(stringResource(R.string.action_pick_cover)) }
            if (!coverPath.isNullOrBlank()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_remove_cover)) }
            }
        }
    }
}

/** Рейтинг звёздами: тап по звезде ставит оценку, тап по той же — снимает. */
@Composable
private fun RatingPicker(
    rating: Int?,
    onRating: (Int?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.label_rating),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        (1..5).forEach { value ->
            IconButton(onClick = { onRating(if (rating == value) null else value) }) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.label_rating),
                    tint = if (rating != null && value <= rating) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
