package ru.antonkw.kwbooktrack.ui.importer

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.importer.ImportFormatDetector
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoResult
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoZipUseCase
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Локализованное сообщение для UI. Для известных ошибок — [res] (плюс опциональный [arg]).
 * Для неизвестных — [raw] (e.message). Compose-слой сам выбирает, что показать.
 */
sealed interface UiText {
    data class Res(@StringRes val res: Int, val arg: String? = null) : UiText
    data class Raw(val raw: String) : UiText
}

data class ImportUiState(
    val inProgress: Boolean = false,
    val result: ImportTellicoResult? = null,
    val error: UiText? = null,
)

@HiltViewModel
class TellicoImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importTellicoZipUseCase: ImportTellicoZipUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun importFromUri(uri: Uri) {
        if (_state.value.inProgress) return
        // copy() leaves result intact — keeps the previous result visible while
        // the new import runs instead of blanking it on every picker tap.
        _state.value = _state.value.copy(inProgress = true, error = null)
        viewModelScope.launch {
            val cacheDir = File(context.cacheDir, "imports").apply { mkdirs() }
            val tempZip = File(cacheDir, "tellico-${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempZip).use { output -> input.copyTo(output) }
                } ?: run {
                    _state.value = _state.value.copy(
                        inProgress = false,
                        error = UiText.Res(R.string.tellico_import_error_read),
                    )
                    return@launch
                }
                val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                // Формат определяем по содержимому: `.tc` — это zip, но расширение
                // у него то же, что у обычного архива.
                val format = ImportFormatDetector.detect(tempZip)
                val result = importTellicoZipUseCase(tempZip, format, coversDir)
                _state.value = ImportUiState(inProgress = false, result = result)
            } catch (e: Exception) {
                val msg = e.message
                _state.value = _state.value.copy(
                    inProgress = false,
                    error = if (msg.isNullOrBlank()) UiText.Res(R.string.tellico_import_error_unknown)
                    else UiText.Raw(msg),
                )
            } finally {
                // tempZip is the only file written to cacheDir/imports for this run;
                // without finally a thrown ImportTellicoZipUseCase would leak it.
                tempZip.delete()
            }
        }
    }

    fun reset() {
        _state.value = ImportUiState()
    }
}


