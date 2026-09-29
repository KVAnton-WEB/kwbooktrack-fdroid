package ru.antonkw.kwbooktrack.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.antonkw.kwbooktrack.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TellicoImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TellicoImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tellico_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tellico_import_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !state.inProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null)
                Text("  Выбрать ZIP-файл")
            }

            if (state.inProgress) {
                Card { Column(modifier = Modifier.padding(16.dp)) { CircularProgressIndicator() } }
            }

            state.result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.tellico_import_done), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.tellico_import_total_format, r.total))
                        Text(stringResource(R.string.tellico_import_imported_format, r.imported))
                        Text(stringResource(R.string.tellico_import_skipped_format, r.skipped))
                        Text(stringResource(R.string.tellico_import_covers_format, r.coversCopied))
                        Text(stringResource(R.string.tellico_import_notes_format, r.notesImported))
                        if (r.dryRun) Text("(dry-run)")
                    }
                }
            }

            state.error?.let { err ->
                val errorText = when (err) {
                    is UiText.Res -> err.arg?.let { stringResource(err.res, it) } ?: stringResource(err.res)
                    is UiText.Raw -> err.raw
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.tellico_import_error), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(errorText)
                    }
                }
            }
        }
    }
}
