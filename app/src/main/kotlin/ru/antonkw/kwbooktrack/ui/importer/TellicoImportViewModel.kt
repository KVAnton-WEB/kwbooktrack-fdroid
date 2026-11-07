package ru.antonkw.kwbooktrack.ui.importer

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.importer.ImportFormat
import ru.antonkw.kwbooktrack.data.importer.ImportFormatDetector
import ru.antonkw.kwbooktrack.data.importer.ImportKwBooktrackZipUseCase
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
    private val importKwBooktrackZipUseCase: ImportKwBooktrackZipUseCase,
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
            // Имя без расширения: файл может оказаться не zip (голый XML или CSV).
            val tempFile = File(cacheDir, "import-${System.currentTimeMillis()}.tmp")
            try {
                // Копирование файла и импорт — тяжёлые: главный поток не блокируем
                // (архив с фото — это десятки мегабайт).
                val copied = withContext(Dispatchers.IO) { copyToTemp(uri, tempFile) }
                if (!copied) {
                    _state.value = ImportUiState(
                        inProgress = false,
                        error = UiText.Res(R.string.tellico_import_error_read),
                    )
                    return@launch
                }
                // Определение формата и импорт — тоже не с главного потока.
                val result = withContext(Dispatchers.IO) { importFile(tempFile) }
                _state.value = importState(result)
            } catch (e: Exception) {
                _state.value = ImportUiState(
                    inProgress = false,
                    error = errorText(e),
                )
            } finally {
                // tempFile is the only file written to cacheDir/imports for this run;
                // without finally a thrown importer would leak it.
                tempFile.delete()
            }
        }
    }

    /**
     * Копирует выбранный файл во временный. false — прочитать не удалось.
     */
    private fun copyToTemp(uri: Uri, target: File): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { source -> FileOutputStream(target).use { source.copyTo(it) } }
        return true
    }

    /**
     * Состояние по результату импорта: счётчики остаются на экране вместе с ошибкой,
     * а «пустой» файл — это не успех, а ошибка.
     */
    private fun importState(result: ImportTellicoResult): ImportUiState {
        val failure = result.error
        return when {
            failure != null -> ImportUiState(
                inProgress = false,
                result = result,
                error = errorText(failure),
            )
            // Ни одной записи в файле — «успешного» импорта не было.
            result.total == 0 && result.imported == 0 -> ImportUiState(
                inProgress = false,
                error = UiText.Res(R.string.tellico_import_error_empty),
            )
            else -> ImportUiState(inProgress = false, result = result)
        }
    }

    /**
     * Формат определяется по содержимому файла, а не по расширению: `.tc` — это zip,
     * как и свой бэкап приложения, а переименованный `.xml` ничем не отличается.
     */
    private suspend fun importFile(file: File): ImportTellicoResult {
        val format = ImportFormatDetector.detect(file)
        // Обложки и фото кладутся в filesDir — апплиер сам создаёт filesDir/covers.
        return when (format) {
            ImportFormat.KWBOOKTRACK_ZIP -> importKwBooktrackZipUseCase(file, context.filesDir)
            ImportFormat.TELLICO_XML, ImportFormat.TELLICO_CSV ->
                importTellicoZipUseCase(file, format, context.filesDir)
        }
    }

    private fun errorText(error: Throwable): UiText =
        error.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) }
            ?: UiText.Res(R.string.tellico_import_error_unknown)

    fun reset() {
        _state.value = ImportUiState()
    }
}
