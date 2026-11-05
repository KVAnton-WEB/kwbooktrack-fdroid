package ru.antonkw.kwbooktrack.data.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.snapshot.ImportAssets
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import java.io.File
import java.io.Reader
import javax.inject.Inject

/**
 * Результат импорта Tellico.
 *
 * [errors] — ошибки по записям: одна сломанная книга не должна ронять импорт
 * целиком, но и молча пропадать не должна.
 */
data class ImportTellicoResult(
    val total: Int,
    val imported: Int,
    val skipped: Int,
    val coversCopied: Int,
    val notesImported: Int,
    val dryRun: Boolean,
    val errors: List<Throwable> = emptyList(),
) {
    /** Последняя ошибка импорта или null, если всё прошло чисто. */
    val error: Throwable? get() = errors.lastOrNull()

    val isSuccess: Boolean get() = errors.isEmpty()
}

/**
 * @param assetRoot корень распакованного архива: оттуда берутся обложки и фото.
 * @param coverOutputDir куда складывать обложки (`<dir>/covers/{bookId}.{ext}`).
 */
data class ImportTellicoOptions(
    val assetRoot: File? = null,
    val coverOutputDir: File? = null,
    val dryRun: Boolean = false,
)

/**
 * Импорт записей Tellico в библиотеку.
 *
 * Записи → снимки → [SnapshotApplier]: обложки, заметки, сессии, теги и фото
 * сливает он же, здесь только разбор.
 */
class ImportTellicoUseCase @Inject constructor(
    private val csvReader: TellicoCsvReader,
    private val mapper: TellicoBookMapper,
    private val applier: SnapshotApplier,
) {

    suspend operator fun invoke(
        records: List<TellicoRecord>,
        options: ImportTellicoOptions = ImportTellicoOptions(),
    ): ImportTellicoResult {
        val assets = ImportAssets(options.assetRoot, options.coverOutputDir)
        var imported = 0
        var skipped = 0
        var coversCopied = 0
        var notesImported = 0
        val errors = mutableListOf<Throwable>()
        for (record in records) {
            val snapshot = mapper.map(record)
            if (snapshot == null) {
                skipped++
                continue
            }
            if (options.dryRun) {
                imported++
                continue
            }
            try {
                // Файловый I/O апплиера — не на потоке вызывающего.
                val stats = withContext(Dispatchers.IO) { applier.apply(snapshot, assets) }
                imported++
                coversCopied += stats.covers
                notesImported += stats.notes + stats.sessions + stats.tags + stats.photos
            } catch (e: Exception) {
                errors += e
                skipped++
            }
        }
        return ImportTellicoResult(
            total = records.size,
            imported = imported,
            skipped = skipped,
            coversCopied = coversCopied,
            notesImported = notesImported,
            dryRun = options.dryRun,
            errors = errors,
        )
    }

    suspend operator fun invoke(
        input: Reader,
        options: ImportTellicoOptions = ImportTellicoOptions(),
    ): ImportTellicoResult = invoke(csvReader.read(input), options)
}
