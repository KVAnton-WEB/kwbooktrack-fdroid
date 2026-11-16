package ru.antonkw.kwbooktrack.ui.about

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.R
import javax.inject.Inject

data class AboutInfo(
    val version: String,
    val totalBooks: Int,
    val finishedBooks: Int,
    val totalMovies: Int,
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val movieRepository: MovieRepository,
    private val appVersionProvider: AppVersionProvider,
) : ViewModel() {
    private val _info = MutableStateFlow(AboutInfo(appVersionProvider.appVersion, 0, 0, 0))
    val info: StateFlow<AboutInfo> = _info.asStateFlow()

    init {
        viewModelScope.launch {
            combine(bookRepository.observeAll(), movieRepository.observeAll()) { books, movies -> books to movies }
                .collect { (books, movies) ->
                val total = books.size
                val finished = books.count { it.status == BookStatus.FINISHED }
                _info.value = AboutInfo(appVersionProvider.appVersion, total, finished, movies.size)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onImportClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = hiltViewModel(),
    exportViewModel: AboutExportViewModel = hiltViewModel(),
) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    val exportState by exportViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // SAF CreateDocument — let the user pick where the backup goes (Drive,
    // Downloads, SD card). Default name carries a timestamp so two exports
    // in the same minute don't silently overwrite each other.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) exportViewModel.exportToUri(uri)
    }

    // Родной формат Tellico: `.tc` — это zip, но mime-тип без расширения,
    // иначе SAF допишет к имени `.zip` (см. AboutExportViewModel.TELLICO_MIME).
    val exportTellicoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(AboutExportViewModel.TELLICO_MIME),
    ) { uri ->
        if (uri != null) exportViewModel.exportTellicoToUri(uri)
    }

    val exportSuccessFormat = stringResource(R.string.export_success_format)
    LaunchedEffect(exportState.lastManifest) {
        val m = exportState.lastManifest ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            exportSuccessFormat.format(m.counts.books, m.counts.photos),
        )
        exportViewModel.acknowledge()
    }
    LaunchedEffect(exportState.error) {
        val err = exportState.error ?: return@LaunchedEffect
        val text = err.message?.let { msg ->
            context.getString(R.string.export_failed_format, msg)
        } ?: context.getString(R.string.export_failed_unknown)
        snackbarHostState.showSnackbar(text)
        exportViewModel.acknowledge()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.about_version), style = MaterialTheme.typography.titleMedium)
                    Text(info.version, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.about_library), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_total_books, info.totalBooks), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.about_total_movies, info.totalMovies), style = MaterialTheme.typography.bodyLarge)
                    val percent = if (info.totalBooks > 0) (info.finishedBooks * 100 / info.totalBooks) else 0
                    Text(
                        stringResource(R.string.about_finished_books, info.finishedBooks, info.totalBooks, percent),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            // Backup & Restore — these actions are infrequent and used to
            // crowd the main screen's top bar; hide them behind the About tab.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_backup_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.about_backup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            exportLauncher.launch(AboutExportViewModel.suggestedFilename())
                        },
                        enabled = !exportState.inProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (exportState.inProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.FileUpload, contentDescription = null)
                        }
                        Text(text = stringResource(R.string.action_export))
                    }
                    OutlinedButton(
                        onClick = {
                            exportTellicoLauncher.launch(AboutExportViewModel.suggestedTellicoFilename())
                        },
                        enabled = !exportState.inProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                        Text(text = stringResource(R.string.action_export_tellico))
                    }
                    OutlinedButton(
                        onClick = onImportClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                        Text(text = stringResource(R.string.action_import_tellico))
                    }
                }
            }
        }
    }
}
