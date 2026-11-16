package ru.antonkw.kwbooktrack.data.exporter

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookExtras
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraArchive
import ru.antonkw.kwbooktrack.data.snapshot.NoteJson
import ru.antonkw.kwbooktrack.data.snapshot.PhotoJson
import ru.antonkw.kwbooktrack.data.snapshot.SessionJson
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import ru.antonkw.kwbooktrack.data.text.formatDecimal
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Serializable
data class ExportManifest(
    val schemaVersion: Int = 1,
    val appVersion: String = "0.1.0",
    val exportedAt: Long,
    val source: String = "kwbooktrack",
    val counts: ExportCounts,
)

/**
 * Счётчики манифеста — что попало в архив.
 *
 * [tags] — число связок «тег — книга», а не число уникальных тегов: тег,
 * привязанный к трём книгам, считается трижды (счёт по снимку). Для экспорта
 * `.tc` — так же.
 */
@Serializable
data class ExportCounts(
    val books: Int,
    val sessions: Int,
    val notes: Int,
    val quotes: Int,
    val photos: Int,
    val tags: Int,
    /** Сколько фильмов попало в архив. Значение по умолчанию — для старых бэкапов. */
    val movies: Int = 0,
)

/**
 * Свой zip-бэкап приложения: `manifest.json` + `books.csv` + `extra.json` +
 * обложки (`covers/`) и фото заметок (`photos/`).
 *
 * `books.csv` — только поля книги; всё, что к книге относится (заметки,
 * сессии чтения, теги, фото, чужие поля Tellico), лежит в `extra.json` по
 * id книги — иначе экспорт терял бы их. Формат `extra.json` читает
 * `ImportKwBooktrackZipUseCase`.
 */
class ExportZipUseCase @Inject constructor(
    private val snapshotBuilder: SnapshotBuilder,
    private val versionProvider: AppVersionProvider,
    private val database: KwBooktrackDatabase,
) {
    /** File-based export — kept for the existing ImportKwBooktrackZipUseCase round-trip tests. */
    suspend operator fun invoke(outputFile: File): ExportManifest =
        invoke(FileOutputStream(outputFile))

    /**
     * Stream-based export — preferred for UI flows that route through a
     * SAF `CreateDocument` URI and write straight into the user-chosen target.
     */
    suspend operator fun invoke(output: OutputStream): ExportManifest {
        // Снимок целиком и в одной транзакции: книги и всё, что к ним
        // относится, должны быть из одного состояния БД — иначе манифест
        // разойдётся с содержимым архива.
        val snapshots = database.withTransaction { snapshotBuilder.buildAll() }
        // Подготовка — stat по каждой книге и каждому фото: это файловый I/O,
        // из `viewModelScope` он шёл бы на главном потоке.
        val prepared = withContext(Dispatchers.IO) { prepare(snapshots) }
        val manifest = ExportManifest(
            appVersion = versionProvider.appVersion,
            exportedAt = System.currentTimeMillis(),
            counts = prepared.counts,
        )

        // Запись архива — блокирующий I/O: на большой библиотеке главный поток
        // (вызов из `viewModelScope`) замрёт, поэтому уводим запись в IO.
        withContext(Dispatchers.IO) {
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                // manifest.json
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JSON.encodeToString(ExportManifest.serializer(), manifest).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                // books.csv (UTF-8 без BOM, разделитель `;`)
                zip.putNextEntry(ZipEntry("books.csv"))
                writeCsv(zip, prepared.books.map { it.book })
                zip.closeEntry()

                // extra.json — заметки, сессии, теги, фото и чужие поля по id книги.
                zip.putNextEntry(ZipEntry("extra.json"))
                val extra = JSON.encodeToString(ExtraArchive.serializer(), extraArchive(prepared.books))
                zip.write(extra.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()

                // covers/ — обложки книг.
                prepared.covers.forEach { (name, file) ->
                    writeFile(zip, "covers/$name", file)
                }

                // photos/ — фото заметок (путь из `extra.json`).
                prepared.photos.forEach { (path, file) ->
                    writeFile(zip, path, file)
                }
            }
        }

        return manifest
    }

    /** `books.csv`: заголовок + строка на книгу. */
    private fun writeCsv(zip: ZipOutputStream, books: List<BookEntity>) {
        zip.write(BOOKS_HEADER.toByteArray(StandardCharsets.UTF_8))
        zip.write("\n".toByteArray(StandardCharsets.UTF_8))
        books.forEach { book ->
            val line = listOf(
                csvField(book.title),
                csvField(book.subtitle.orEmpty()),
                csvField(book.author),
                csvField(book.isbn13.orEmpty()),
                csvField(book.pageCount?.toString().orEmpty()),
                csvField(book.language),
                csvField(book.genre.orEmpty()),
                csvField(book.publisher.orEmpty()),
                csvField(book.year?.toString().orEmpty()),
                csvField(book.coverPath?.substringAfterLast('/').orEmpty()),
                csvField(book.rating?.toString().orEmpty()),
                csvField(book.status.name),
                csvField(epochToDate(book.startedAt)),
                csvField(epochToDate(book.finishedAt)),
                csvField(book.binding.orEmpty()),
                csvField(book.purchasePlace.orEmpty()),
                csvField(book.series.orEmpty()),
                csvField(book.seriesNumber.orEmpty()),
                csvField(book.condition.orEmpty()),
                csvField(book.translator.orEmpty()),
                csvField(book.printYear?.toString().orEmpty()),
                csvField(book.printPages?.let { formatDecimal(it) }.orEmpty()),
                csvField(book.originalLanguage.orEmpty()),
                csvField(book.originalTitle.orEmpty()),
                csvField(book.annotation.orEmpty()),
                csvField(if (book.isGift) "1" else ""),
                csvField(if (book.isLent) "1" else ""),
                csvField(if (book.isSold) "1" else ""),
                csvField(epochToDate(book.purchaseDate)),
                csvField(book.purchasePrice?.let { formatDecimal(it) }.orEmpty()),
                csvField(book.source.name),
                csvField(book.id.toString()),
                csvField(book.createdAt.toString()),
                csvField(book.updatedAt.toString()),
            ).joinToString(";")
            zip.write(line.toByteArray(StandardCharsets.UTF_8))
            zip.write("\n".toByteArray(StandardCharsets.UTF_8))
        }
    }

    /**
     * Снимки → то, что попадёт в архив: книги, обложки, фото и счётчики.
     *
     * Обложка кладётся под именем файла из `cover_path` — импорт ищет её
     * именно по этому имени. Фото без файла в архив не попадает и в
     * `extra.json` не упоминается.
     */
    private fun prepare(snapshots: List<BookSnapshot>): Prepared {
        val books = mutableListOf<BookSnapshot>()
        val covers = linkedMapOf<String, File>()
        val photos = linkedMapOf<String, File>()
        var sessions = 0
        var notes = 0
        var quotes = 0
        var tags = 0
        var photoCount = 0

        for (snapshot in snapshots) {
            snapshot.book.coverPath?.let { path ->
                val file = File(path)
                if (file.isFile) covers.putIfAbsent(file.name, file)
            }
            val withFiles = snapshot.photos.filter { File(it.localPath).isFile }
            withFiles.forEach { photo -> photos.putIfAbsent(photo.archivePath, File(photo.localPath)) }
            books += snapshot.copy(photos = withFiles)
            sessions += snapshot.sessions.size
            notes += snapshot.notes.count { it.kind == NoteKind.NOTE }
            quotes += snapshot.notes.count { it.kind == NoteKind.QUOTE }
            tags += snapshot.tags.size
            photoCount += withFiles.size
        }

        val counts = ExportCounts(
            books = books.size,
            sessions = sessions,
            notes = notes,
            quotes = quotes,
            photos = photoCount,
            tags = tags,
        )
        return Prepared(books, covers, photos, counts)
    }

    /**
     * Файл в архив.
     *
     * Файл мог исчезнуть между подготовкой и записью (пользователь удалил его
     * с диска) — тогда его пропускаем, а не роняем весь экспорт: как с
     * отсутствующими фото.
     */
    private fun writeFile(zip: ZipOutputStream, path: String, file: File) {
        val input = try {
            file.inputStream()
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "файл пропущен: $path", e)
            return
        }
        zip.putNextEntry(ZipEntry(path))
        input.use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** `extra.json`: содержимое по id книги (строкой). */
    private fun extraArchive(books: List<BookSnapshot>): ExtraArchive =
        ExtraArchive(
            books = books.associate { snapshot ->
                snapshot.book.id.toString() to BookExtras(
                    notes = snapshot.notes.map { note ->
                        NoteJson(
                            kind = note.kind.name,
                            page = note.page,
                            body = note.body,
                            createdAt = note.createdAt,
                        )
                    },
                    sessions = snapshot.sessions.map { session ->
                        SessionJson(
                            startedAt = session.startedAt,
                            endedAt = session.endedAt,
                            startPage = session.startPage,
                            endPage = session.endPage,
                            durationSec = session.durationSec,
                            note = session.note,
                        )
                    },
                    tags = snapshot.tags,
                    photos = snapshot.photos.map { photo ->
                        PhotoJson(
                            file = photo.archivePath,
                            noteIndex = photo.noteIndex,
                            createdAt = photo.createdAt,
                        )
                    },
                    extraFields = snapshot.extraFields,
                )
            },
        )

    /** Подготовленный архив: книги, обложки, фото и счётчики. */
    private data class Prepared(
        val books: List<BookSnapshot>,
        /** Имя файла в архиве → файл обложки. */
        val covers: Map<String, File>,
        /** Путь в архиве (`photos/{md5}.{ext}`) → файл фото. */
        val photos: Map<String, File>,
        val counts: ExportCounts,
    )

    private fun csvField(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(';') || escaped.contains('"') || escaped.contains('\n')) "\"$escaped\"" else escaped
    }

    private fun epochToDate(epoch: Long?): String {
        if (epoch == null) return ""
        return DateTimeFormatter.ISO_LOCAL_DATE.format(epoch.toLocalDate())
    }

    companion object {
        /**
         * `encodeDefaults = true`: ключи `extra.json` пишутся всегда — формат
         * читается по именам, и «пустое» значение (пустой текст заметки,
         * `page: null`) не должно выпадать из JSON.
         */
        private val JSON = Json { encodeDefaults = true }

        private const val TAG = "ExportZip"

        /**
         * Заголовок `books.csv`.
         *
         * Последние две колонки — метки времени книги в миллисекундах epoch
         * (как их хранит БД): без них импорт ставил бы «создано/изменено» в
         * «сейчас» и архив терял бы историю книги.
         */
        const val BOOKS_HEADER = "title;subtitle;author;isbn13;page_count;language;genre;publisher;year;cover_path;rating;status;started_at;finished_at;binding;purchase_place;series;series_number;condition;translator;print_year;print_pages;original_language;original_title;annotation;is_gift;is_lent;is_sold;purchase_date;purchase_price;source;id;created_at;updated_at"
    }
}

interface AppVersionProvider {
    val appVersion: String
}
