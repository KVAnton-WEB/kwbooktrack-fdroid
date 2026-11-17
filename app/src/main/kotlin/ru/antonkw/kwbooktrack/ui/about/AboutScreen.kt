package ru.antonkw.kwbooktrack.ui.about

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.media.MediaTypeStore
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.settings.AppLanguage
import ru.antonkw.kwbooktrack.data.settings.SettingsStore
import ru.antonkw.kwbooktrack.data.settings.ThemeMode
import ru.antonkw.kwbooktrack.R
import javax.inject.Inject
import ru.antonkw.kwbooktrack.ui.common.FilterDropdown
import ru.antonkw.kwbooktrack.ui.common.UrlText
import ru.antonkw.kwbooktrack.ui.common.copyToClipboard
import ru.antonkw.kwbooktrack.ui.common.findActivity
import ru.antonkw.kwbooktrack.ui.common.labelRes
import ru.antonkw.kwbooktrack.ui.common.mediaLabelRes

/** Автор и каналы связи: подпись + ссылка, открывается в браузере. */
private val AUTHOR_LINKS = listOf(
    R.string.about_link_github to "https://github.com/KVAnton-WEB/kwbooktrack",
    R.string.about_link_telegram to "https://t.me/AntonKw",
)

/** Способы доната: подпись + ссылка. */
private val DONATION_LINKS = listOf(
    R.string.about_link_boosty to "https://boosty.to/kvanton/donate",
    R.string.about_link_dalink to "https://dalink.to/ant0nk",
)

/** Криптокошельки: сеть, активы, адрес (тап — копирование в буфер). */
private val CRYPTO_WALLETS = listOf(
    Triple("Ethereum", "ETH / USDT / USDC", "0x9a68188c912c70fa4eecb7aaf651c7318047b2a2"),
    Triple("Polygon", "POL / USDT / USDC", "0x9A68188C912c70Fa4EeCB7AAF651C7318047b2A2"),
    Triple("Bitcoin", "BTC", "bc1qr0m6alqjk9zzy29ma0wpz66uas0dyrxy9hdr7a"),
    Triple("Solana", "SOL / USDT / USDC", "ck5YP1JW8hhoMnxdUWPUVnkRNEZWD3EQVJjm33Ec9ma"),
)

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
    private val mediaTypeStore: MediaTypeStore,
    private val appVersionProvider: AppVersionProvider,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _info = MutableStateFlow(AboutInfo(appVersionProvider.appVersion, 0, 0, 0))
    val info: StateFlow<AboutInfo> = _info.asStateFlow()

    /**
     * Тип коллекции, которая уйдёт в экспорт Tellico.
     *
     * В файле `.tc` помещается одна коллекция — Tellico держит одну на файл
     * и при двух смешал бы записи, поэтому видно, что именно выгрузится.
     */
    val mediaType: StateFlow<MediaType> = mediaTypeStore.type

    fun setMediaType(type: MediaType) {
        mediaTypeStore.set(type)
    }

    /** Тема оформления: по системе, светлая или тёмная. */
    val themeMode: StateFlow<ThemeMode> = settingsStore.themeMode

    /** Язык интерфейса: по системе, русский или английский. */
    val language: StateFlow<AppLanguage> = settingsStore.language

    fun setThemeMode(mode: ThemeMode) {
        settingsStore.setThemeMode(mode)
    }

    fun setLanguage(language: AppLanguage) {
        settingsStore.setLanguage(language)
    }

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
    val mediaType by viewModel.mediaType.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
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

    // Строки читаем до эффекта: stringResource нельзя звать из LaunchedEffect.
    val exportSuccessZipFormat = stringResource(R.string.export_success_zip_format)
    val exportSuccessBooksFormat = stringResource(R.string.export_success_books_format)
    val exportSuccessMoviesFormat = stringResource(R.string.export_success_movies_format)
    LaunchedEffect(exportState.lastManifest) {
        val m = exportState.lastManifest ?: return@LaunchedEffect
        // Подпись зависит от того, что выгрузили: в своём zip-бэкапе и книги,
        // и фильмы, а в родном `.tc` — одна коллекция.
        val text = when {
            exportState.exportedKind == ExportKind.TELLICO &&
                exportState.exportedMediaType == MediaType.MOVIES ->
                exportSuccessMoviesFormat.format(m.counts.movies)
            exportState.exportedKind == ExportKind.TELLICO ->
                exportSuccessBooksFormat.format(m.counts.books)
            else ->
                exportSuccessZipFormat.format(m.counts.books, m.counts.movies, m.counts.photos)
        }
        snackbarHostState.showSnackbar(text)
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
            // Оформление и язык — вручную: светлая тема и два языка интерфейса.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FilterDropdown(
                        label = stringResource(R.string.settings_theme_label),
                        selectedLabel = stringResource(themeMode.labelRes),
                        options = ThemeMode.entries.map { stringResource(it.labelRes) },
                        onSelected = { index -> viewModel.setThemeMode(ThemeMode.entries[index]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilterDropdown(
                        label = stringResource(R.string.settings_language_label),
                        selectedLabel = stringResource(language.labelRes),
                        options = AppLanguage.entries.map { stringResource(it.labelRes) },
                        onSelected = { index ->
                            viewModel.setLanguage(AppLanguage.entries[index])
                            // Язык подменяется в attachBaseContext — без
                            // пересоздания activity ресурсы останутся прежними.
                            context.findActivity()?.recreate()
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                    Text(
                        text = stringResource(R.string.export_collection_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilterDropdown(
                        label = stringResource(R.string.export_collection_label),
                        selectedLabel = stringResource(mediaType.mediaLabelRes),
                        options = MediaType.entries.map { stringResource(it.mediaLabelRes) },
                        onSelected = { index -> viewModel.setMediaType(MediaType.entries[index]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
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
            // Автор и донаты.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_author_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AUTHOR_LINKS.forEach { (labelRes, url) -> LinkWithLabel(labelRes, url) }
                    Text(
                        text = stringResource(R.string.about_support_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    DONATION_LINKS.forEach { (labelRes, url) -> LinkWithLabel(labelRes, url) }
                    Text(
                        text = stringResource(R.string.about_crypto_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    CRYPTO_WALLETS.forEach { (network, asset, address) ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(network, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                asset,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .clickable {
                                        copyToClipboard(context, address, R.string.about_crypto_title)
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Подпись ссылки и сама ссылка (открывается в браузере). */
@Composable
private fun LinkWithLabel(labelRes: Int, url: String) {
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UrlText(url)
    }
}
