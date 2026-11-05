package ru.antonkw.kwbooktrack.ui.about

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.exporter.ExportManifest
import ru.antonkw.kwbooktrack.data.exporter.ExportTellicoTcUseCase
import ru.antonkw.kwbooktrack.data.exporter.ExportZipUseCase
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class AboutExportState(
    val inProgress: Boolean = false,
    val lastManifest: ExportManifest? = null,
    val error: Throwable? = null,
)

/**
 * Owns the export action for [AboutScreen]. The actual file write goes through
 * a SAF `CreateDocument` URI supplied by the UI — the ViewModel never picks
 * the destination itself, so a buggy SAF picker doesn't leak partial files
 * anywhere on disk.
 */
@HiltViewModel
class AboutExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportZipUseCase: ExportZipUseCase,
    private val exportTellicoUseCase: ExportTellicoTcUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(AboutExportState())
    val state: StateFlow<AboutExportState> = _state.asStateFlow()

    /** Свой zip-бэкап приложения. */
    fun exportToUri(uri: Uri) = export(uri) { exportZipUseCase(it) }

    /** Родной формат Tellico: `.tc` (zip с `tellico.xml`). */
    fun exportTellicoToUri(uri: Uri) = export(uri) { exportTellicoUseCase(it) }

    private fun export(uri: Uri, write: suspend (OutputStream) -> ExportManifest) {
        if (_state.value.inProgress) return
        _state.value = AboutExportState(inProgress = true)
        viewModelScope.launch {
            val outcome = runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Cannot open output stream for $uri")
                output.use { write(it) }
            }
            _state.value = if (outcome.isSuccess) {
                AboutExportState(lastManifest = outcome.getOrNull())
            } else {
                // Keep the Throwable itself — UI shows the message when present
                // and falls back to a localized "unknown error" string when it
                // isn't. Some SecurityException paths and third-party libs
                // ship null messages; rendering nothing on those would be the
                // worst possible feedback.
                AboutExportState(error = outcome.exceptionOrNull())
            }
        }
    }

    fun acknowledge() {
        _state.value = AboutExportState()
    }

    companion object {
        /**
         * Default name proposed by the SAF picker, e.g.
         * `kwbooktrack-backup-2024-09-30_15-30-00.zip`. Local time, seconds
         * precision so a user can export twice in the same minute and not
         * silently overwrite.
         */
        fun suggestedFilename(now: LocalDateTime = LocalDateTime.now()): String {
            val ts = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            return "kwbooktrack-backup-$ts.zip"
        }

        /** То же имя для родного формата Tellico. */
        fun suggestedTellicoFilename(now: LocalDateTime = LocalDateTime.now()): String {
            val ts = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            return "kwbooktrack-tellico-$ts.tc"
        }
    }
}
