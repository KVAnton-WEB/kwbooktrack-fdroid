package ru.antonkw.kwbooktrack.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.R
import javax.inject.Inject

data class AboutInfo(
    val version: String,
    val totalBooks: Int,
    val finishedBooks: Int,
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val appVersionProvider: AppVersionProvider,
) : ViewModel() {
    private val _info = MutableStateFlow(AboutInfo(appVersionProvider.appVersion, 0, 0))
    val info: StateFlow<AboutInfo> = _info.asStateFlow()

    init {
        viewModelScope.launch {
            bookRepository.observeAll().collect { books ->
                val total = books.size
                val finished = books.count { it.status == BookStatus.FINISHED }
                _info.value = AboutInfo(appVersionProvider.appVersion, total, finished)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val info by viewModel.info.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
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
                    val percent = if (info.totalBooks > 0) (info.finishedBooks * 100 / info.totalBooks) else 0
                    Text(
                        stringResource(R.string.about_finished_books, info.finishedBooks, info.totalBooks, percent),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
