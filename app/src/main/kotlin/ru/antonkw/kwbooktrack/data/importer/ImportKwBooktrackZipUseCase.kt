package ru.antonkw.kwbooktrack.data.importer

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.exporter.ExportManifest
import ru.antonkw.kwbooktrack.data.snapshot.BookExtras
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraArchive
import ru.antonkw.kwbooktrack.data.snapshot.ImportAssets
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SessionSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.text.parseDecimal
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Импорт своего zip-бэкапа приложения (`manifest.json` + `books.csv` + `extra.json`).
 *
 * Книги и всё, что к ним относится (заметки, сессии, теги, фото, чужие поля),
 * собираются в снимки и вливаются [SnapshotApplier] — тем же путём, что и
 * импорт Tellico, поэтому повторный импорт не плодит дубли.
 *
 * Архив распаковывается во временный каталог рядом с выходным — оттуда
 * берутся обложки и фото. Каталог удаляется в `finally`.
 */
class ImportKwBooktrackZipUseCase @Inject constructor(
    private val applier: SnapshotApplier,
) {

    /**
     * Импортирует архив в библиотеку.
     *
     * @param zipFile архив бэкапа.
     * @param outputDir каталог файлов приложения (`filesDir`): туда кладутся обложки.
     */
    suspend operator fun invoke(zipFile: File, outputDir: File): ImportTellicoResult {
        if (!zipFile.exists()) {
            return failure(IllegalStateException("файл не найден: ${zipFile.absolutePath}"))
        }
        val staging = File(outputDir, ".import-staging")
        try {
            val prepared = withContext(Dispatchers.IO) { prepare(zipFile, staging) }
            // Корень ассетов — распакованный архив; обложки кладутся в outputDir.
            val assets = ImportAssets(staging, outputDir)
            var imported = 0
            var skipped = 0
            var coversCopied = 0
            var notesImported = 0
            val errors = mutableListOf<Throwable>()
            for (row in prepared.rows) {
                val book = row.book
                if (book == null) {
                    skipped++
                    continue
                }
                try {
                    val snapshot = snapshot(book, prepared.extras[row.id])
                    val stats = withContext(Dispatchers.IO) { applier.apply(snapshot, assets) }
                    imported++
                    coversCopied += stats.covers
                    notesImported += stats.notes + stats.sessions + stats.tags + stats.photos
                } catch (e: CancellationException) {
                    // Отмена импорта — не ошибка записи: пробрасываем дальше.
                    throw e
                } catch (e: Exception) {
                    errors += e
                    skipped++
                }
            }
            return ImportTellicoResult(
                total = prepared.rows.size,
                imported = imported,
                skipped = skipped,
                coversCopied = coversCopied,
                notesImported = notesImported,
                dryRun = false,
                errors = errors,
                warnings = prepared.warnings,
            )
        } catch (e: CancellationException) {
            // Отмена импорта — не «ошибка архива».
            throw e
        } catch (e: IllegalStateException) {
            // Архив не тот или не читается — это результат импорта, а не падение.
            return failure(e)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Распаковывает архив и читает его части.
     *
     * @throws IllegalStateException если архив не нашёлся или не нашёлся `manifest.json`.
     */
    private fun prepare(zipFile: File, staging: File): Prepared {
        staging.deleteRecursively()
        staging.mkdirs()
        unzip(zipFile, staging)

        val manifestFile = findFile(staging, "manifest.json")
            ?: throw IllegalStateException("manifest.json не найден в архиве")
        val manifest = try {
            JSON.decodeFromString(ExportManifest.serializer(), manifestFile.readText())
        } catch (e: Exception) {
            throw IllegalStateException("manifest.json не прочитан: ${e.message}")
        }
        if (manifest.source != "kwbooktrack") {
            throw IllegalStateException("ZIP не является экспортом kwbooktrack (source=${manifest.source})")
        }

        val csvFile = findFile(staging, "books.csv")
            ?: throw IllegalStateException("books.csv не найден в архиве")
        val rows = readRows(csvFile)
        // `extra.json` может отсутствовать (старый бэкап) — тогда импортируются только книги.
        val extrasFile = findFile(staging, "extra.json")
        val extras = readExtras(extrasFile)
        return Prepared(rows, extras, warnings(manifest, extrasFile, extras, rows))
    }

    /**
     * Предупреждения импорта.
     *
     * Старый формат экспорта (до `extra.json`) не выгружал ни сессий чтения,
     * ни заметок, ни цитат — хотя манифест честно считал их. Молчать об этом
     * нельзя: человек видит «импорт прошёл», а прогресса нет.
     *
     * Второй случай — `extra.json` есть, но привязать его не к чему: в
     * `books.csv` нет колонки `id`. Данные в архиве есть, но достать их
     * нельзя — это тоже потеря, о ней тоже надо сказать.
     */
    private fun warnings(
        manifest: ExportManifest,
        extrasFile: File?,
        extras: Map<Long, BookExtras>,
        rows: List<BookRow>,
    ): List<String> {
        val result = mutableListOf<String>()
        val counts = manifest.counts
        val lost = counts.sessions + counts.notes + counts.quotes + counts.photos + counts.tags
        if (extrasFile == null && lost > 0) {
            result += MISSING_EXTRA_WARNING.format(
                counts.sessions,
                counts.notes + counts.quotes,
            )
        }
        if (extrasFile != null && extras.isNotEmpty() && rows.none { it.id != null }) {
            result += UNBOUND_EXTRA_WARNING
        }
        return result
    }

    /** Строки `books.csv`: id книги в архиве и сама книга (null — без названия). */
    private class BookRow(val id: Long?, val book: BookEntity?)

    /** Прочитанный архив: книги, `extra.json` по id книги и предупреждения. */
    private class Prepared(
        val rows: List<BookRow>,
        val extras: Map<Long, BookExtras>,
        val warnings: List<String>,
    )

    /** Строка `books.csv` — значения колонок по имени. */
    private class CsvRow(
        private val record: CSVRecord,
        private val indexes: Map<String, Int>,
    ) {
        /**
         * Значение колонки.
         *
         * Колонки может не быть (старый бэкап) или строка может быть короче
         * заголовка — тогда значения нет.
         */
        fun cell(name: String): String? {
            val index = indexes[name] ?: return null
            if (index >= record.size()) return null
            return record[index].trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun readRows(csvFile: File): List<BookRow> = try {
        CSVParser(csvFile.reader(), FORMAT).use { parser ->
            // Индекс колонок: колонки может не быть (старый бэкап), а строка
            // может быть короче заголовка — значение тогда просто отсутствует.
            val indexes = parser.headerNames.withIndex().associate { (index, name) -> name to index }
            parser.records.map { record ->
                val row = CsvRow(record, indexes)
                BookRow(id = row.cell("id")?.toLongOrNull(), book = row.toBook())
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("books.csv не прочитан: ${e.message}")
    }

    /**
     * `extra.json` — заметки, сессии, теги, фото и чужие поля по id книги.
     *
     * Ключ — id книги строкой. Битый `extra.json` — не повод ронять импорт:
     * книги из `books.csv` импортируются без недостающих данных.
     */
    private fun readExtras(file: File?): Map<Long, BookExtras> {
        if (file == null) return emptyMap()
        val archive = try {
            JSON.decodeFromString(ExtraArchive.serializer(), file.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extra.json не прочитан", e)
            return emptyMap()
        }
        return archive.books.mapNotNull { (id, extras) ->
            id.toLongOrNull()?.let { it to extras }
        }.toMap()
    }

    /** Снимок книги: сама книга + то, что лежит в `extra.json`. */
    private fun snapshot(book: BookEntity, extras: BookExtras?): BookSnapshot = BookSnapshot(
        book = book,
        notes = extras?.noteSnapshots().orEmpty(),
        sessions = extras?.sessionSnapshots().orEmpty(),
        tags = extras?.tags.orEmpty(),
        photos = extras?.photoSnapshots().orEmpty(),
        extraFields = extras?.extraFields.orEmpty(),
    )

    /** Строка `books.csv` → книга. */
    private fun CsvRow.toBook(): BookEntity? {
        val title = cell("title").orEmpty()
        if (title.isEmpty()) return null
        val now = System.currentTimeMillis()
        return BookEntity(
            title = title,
            subtitle = cell("subtitle"),
            author = cell("author") ?: "(автор неизвестен)",
            isbn13 = cell("isbn13"),
            pageCount = cell("page_count")?.toIntOrNull(),
            language = cell("language") ?: "ru",
            genre = cell("genre"),
            publisher = cell("publisher"),
            year = cell("year")?.toIntOrNull(),
            // Имя файла обложки в архиве: локальный путь источника нам не нужен.
            coverPath = cell("cover_path")?.substringAfterLast('/'),
            rating = cell("rating")?.toIntOrNull(),
            status = cell("status")?.let { runCatching { BookStatus.valueOf(it) }.getOrNull() } ?: BookStatus.WISHLIST,
            startedAt = parseDate(cell("started_at")),
            finishedAt = parseDate(cell("finished_at")),
            // Метки времени — в миллисекундах epoch, как их хранит БД.
            // В старых бэкапах колонок нет — тогда «сейчас», как было раньше.
            createdAt = cell("created_at")?.toLongOrNull() ?: now,
            updatedAt = cell("updated_at")?.toLongOrNull() ?: now,
            source = cell("source")?.let { runCatching { BookSource.valueOf(it) }.getOrNull() } ?: BookSource.MANUAL,
            purchaseDate = parseDate(cell("purchase_date")),
            purchasePrice = cell("purchase_price")?.let { parseDecimal(it) },
            binding = cell("binding"),
            purchasePlace = cell("purchase_place"),
            series = cell("series"),
            seriesNumber = cell("series_number"),
            condition = cell("condition"),
            translator = cell("translator"),
            printYear = cell("print_year")?.toIntOrNull(),
            printPages = cell("print_pages")?.let { parseDecimal(it) },
            originalLanguage = cell("original_language"),
            originalTitle = cell("original_title"),
            annotation = cell("annotation"),
            isGift = isTrue(cell("is_gift")),
            isLent = isTrue(cell("is_lent")),
            isSold = isTrue(cell("is_sold")),
        )
    }

    /** Дата: ISO (`2024-01-01`) или epoch millis. */
    private fun parseDate(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        return runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).toEpochMs() }.getOrNull()
    }

    /** Флаги экспорта: «1», «true», «да», «yes». */
    private fun isTrue(raw: String?): Boolean = raw != null && raw.lowercase() in TRUE_VALUES

    /** Файл архива по имени: сначала в корне, потом где угодно внутри. */
    private fun findFile(root: File, name: String): File? =
        File(root, name).takeIf { it.isFile }
            ?: root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    /** Распаковка архива с защитой от выхода за пределы каталога (zip-slip). */
    private fun unzip(zip: File, target: File) {
        val root = try {
            target.canonicalPath
        } catch (e: IOException) {
            throw IllegalStateException("архив не распакован: ${e.message}")
        }
        try {
            ZipFile(zip).use { archive ->
                for (entry in archive.entries()) {
                    val out = File(target, entry.name)
                    // Путь из архива не должен уводить за пределы распаковки.
                    if (!out.canonicalPath.startsWith(root + File.separator)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException("архив не распакован: ${e.message}")
        }
    }

    private fun failure(error: Throwable): ImportTellicoResult = ImportTellicoResult(
        total = 0,
        imported = 0,
        skipped = 0,
        coversCopied = 0,
        notesImported = 0,
        dryRun = false,
        errors = listOf(error),
    )

    private companion object {
        const val TAG = "ImportKwBooktrackZip"
        val TRUE_VALUES = setOf("1", "true", "да", "yes")

        /**
         * В архиве нет прогресса чтения, заметок и цитат: их не выгрузила
         * старая версия приложения (до `extra.json`), хотя манифест считал их.
         */
        const val MISSING_EXTRA_WARNING =
            "В бэкапе нет прогресса чтения, заметок и цитат: старая версия приложения " +
                "не записывала их в архив (по манифесту их было — сессий %1\$d, " +
                "заметок и цитат %2\$d). Импортированы только книги — прогресс придётся " +
                "восстановить вручную или перенести из .tc-экспорта."

        /**
         * `extra.json` в архиве есть, но привязать его не к чему: в `books.csv`
         * нет колонки `id`.
         */
        const val UNBOUND_EXTRA_WARNING =
            "Заметки и сессии в архиве не привязались к книгам: в books.csv нет " +
                "колонки id (старый формат бэкапа). Прогресс и заметки не перенесены."

        val JSON = Json { ignoreUnknownKeys = true }

        val FORMAT: CSVFormat = CSVFormat.Builder.create(CSVFormat.EXCEL)
            .setDelimiter(';')
            .setQuote('"')
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
    }
}

private fun BookExtras.noteSnapshots(): List<NoteSnapshot> = notes.map { note ->
    NoteSnapshot(
        kind = runCatching { NoteKind.valueOf(note.kind) }.getOrDefault(NoteKind.NOTE),
        body = note.body,
        page = note.page,
        createdAt = note.createdAt,
    )
}

private fun BookExtras.sessionSnapshots(): List<SessionSnapshot> = sessions.map { session ->
    SessionSnapshot(
        startedAt = session.startedAt,
        endedAt = session.endedAt,
        startPage = session.startPage,
        endPage = session.endPage,
        durationSec = session.durationSec,
        note = session.note,
    )
}

private fun BookExtras.photoSnapshots(): List<PhotoSnapshot> = photos.map { photo ->
    PhotoSnapshot(
        archivePath = photo.file,
        noteIndex = photo.noteIndex,
        createdAt = photo.createdAt,
    )
}
