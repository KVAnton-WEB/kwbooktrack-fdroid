package ru.antonkw.kwbooktrack.data.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.snapshot.ApplyStats
import ru.antonkw.kwbooktrack.data.snapshot.ImportAssets
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoFields
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import java.io.File
import java.io.Reader
import javax.inject.Inject

/**
 * Результат импорта Tellico.
 *
 * [errors] — ошибки по записям: одна сломанная книга не должна ронять импорт
 * целиком, но и молча пропадать не должна.
 *
 * [warnings] — не ошибки, а предупреждения для пользователя: импорт прошёл,
 * но часть данных перенести не удалось (например, обложки лежат рядом с
 * архивом, а не внутри него).
 */
data class ImportTellicoResult(
    val total: Int,
    val imported: Int,
    val skipped: Int,
    val coversCopied: Int,
    val notesImported: Int,
    val dryRun: Boolean,
    /** Сколько из импортированных записей — фильмы. */
    val movies: Int = 0,
    val errors: List<Throwable> = emptyList(),
    val warnings: List<String> = emptyList(),
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
 *
 * Тип коллекции решает, кто разбирает запись: `type="2"` — книги, `type="3"` —
 * фильмы. Остальные типы ридер не отдаёт.
 */
class ImportTellicoUseCase @Inject constructor(
    private val csvReader: TellicoCsvReader,
    private val mapper: TellicoBookMapper,
    private val movieMapper: TellicoMovieMapper,
    private val applier: SnapshotApplier,
) {

    suspend operator fun invoke(
        records: List<TellicoRecord>,
        options: ImportTellicoOptions = ImportTellicoOptions(),
    ): ImportTellicoResult {
        val assets = ImportAssets(options.assetRoot, options.coverOutputDir)
        var imported = 0
        var skipped = 0
        var movies = 0
        var coversCopied = 0
        var notesImported = 0
        var coversReferenced = 0
        val errors = mutableListOf<Throwable>()
        for (record in records) {
            val isMovie = record.collectionType == TellicoFields.COLLECTION_MOVIES
            val coverPath: String?
            val stats: ApplyStats
            if (isMovie) {
                val snapshot = movieMapper.map(record)
                if (snapshot == null) {
                    skipped++
                    continue
                }
                coverPath = snapshot.movie.coverPath
                if (options.dryRun) {
                    imported++
                    movies++
                    continue
                }
                try {
                    // Файловый I/O апплиера — не на потоке вызывающего.
                    stats = withContext(Dispatchers.IO) { applier.apply(snapshot, assets) }
                    imported++
                    movies++
                } catch (e: CancellationException) {
                    // Отмена импорта — не ошибка записи: пробрасываем дальше.
                    throw e
                } catch (e: Exception) {
                    errors += e
                    skipped++
                    continue
                }
            } else {
                val snapshot = mapper.map(record)
                if (snapshot == null) {
                    skipped++
                    continue
                }
                coverPath = snapshot.book.coverPath
                if (options.dryRun) {
                    imported++
                    continue
                }
                try {
                    stats = withContext(Dispatchers.IO) { applier.apply(snapshot, assets) }
                    imported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += e
                    skipped++
                    continue
                }
            }
            if (!coverPath.isNullOrBlank()) coversReferenced++
            coversCopied += stats.covers
            notesImported += stats.notes + stats.sessions + stats.tags + stats.photos
        }
        return ImportTellicoResult(
            total = records.size,
            imported = imported,
            skipped = skipped,
            coversCopied = coversCopied,
            notesImported = notesImported,
            dryRun = options.dryRun,
            movies = movies,
            errors = errors,
            warnings = warnings(coversReferenced, coversCopied, options.dryRun),
        )
    }

    /**
     * Предупреждения импорта.
     *
     * Обложки, на которые ссылаются записи, но которых нет в архиве: Tellico
     * хранит картинки **рядом** с файлом коллекции (в папке `<имя>_files/`),
     * а не внутри архива `.tc` — молчать об этом нельзя, пользователь ждёт
     * переноса обложек. Предупреждение и про часть обложек: одна найденная из
     * двух — тоже потеря, и про это своё сообщение: «обложки не найдены» при
     * половине перенесённых обложек вводит в заблуждение.
     */
    private fun warnings(coversReferenced: Int, coversCopied: Int, dryRun: Boolean): List<String> {
        if (dryRun || coversReferenced == 0 || coversCopied >= coversReferenced) return emptyList()
        return if (coversCopied == 0) {
            listOf(NO_COVERS_WARNING)
        } else {
            listOf(PARTIAL_COVERS_WARNING.format(coversCopied, coversReferenced))
        }
    }

    suspend operator fun invoke(
        input: Reader,
        options: ImportTellicoOptions = ImportTellicoOptions(),
    ): ImportTellicoResult = invoke(csvReader.read(input), options)

    private companion object {
        /**
         * Обложки не нашлись: у архивов, писанных самим Tellico, картинки лежат
         * рядом с файлом коллекции (в папке `<имя>_files/`), а не внутри `.tc`.
         */
        const val NO_COVERS_WARNING =
            "Обложки не найдены: Tellico хранит картинки рядом с файлом коллекции " +
                "(в папке «<имя>_files»), а не внутри архива. Скопируйте архив вместе " +
                "с папкой картинок и повторите импорт."

        /** Часть обложек перенеслась, часть — нет. */
        const val PARTIAL_COVERS_WARNING =
            "Обложек перенесено %1\$d из %2\$d: часть картинок потеряна. Tellico " +
                "хранит их рядом с файлом коллекции (в папке «<имя>_files») — проверьте, " +
                "что папка скопирована целиком."
    }
}
