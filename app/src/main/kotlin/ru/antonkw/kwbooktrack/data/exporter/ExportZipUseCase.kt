package ru.antonkw.kwbooktrack.data.exporter

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookExtras
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraArchive
import ru.antonkw.kwbooktrack.data.snapshot.MovieExtras
import ru.antonkw.kwbooktrack.data.snapshot.MovieSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.NoteJson
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.PhotoJson
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SessionJson
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import ru.antonkw.kwbooktrack.data.text.formatDecimal
import ru.antonkw.kwbooktrack.data.time.toDayDate
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
    /** Сколько обложек попало в архив. */
    val covers: Int = 0,
    /**
     * Сколько обложек потерялось: в строке ссылка есть, а файла на диске нет.
     *
     * Раньше обложки не считались вообще, и потерянная обложка была видна
     * только тем, что картинка не грузилась. Теперь это видно в счётчиках.
     */
    val coversMissing: Int = 0,
    /** Сколько рецензий попало в архив. */
    val reviews: Int = 0,
)

/**
 * Свой zip-бэкап приложения: `manifest.json` + `books.csv` + `movies.csv` +
 * `extra.json` + обложки (`covers/`) и фото заметок (`photos/`).
 *
 * `books.csv` — только поля книги; всё, что к книге относится (заметки,
 * сессии чтения, теги, фото, чужие поля Tellico), лежит в `extra.json` по
 * id книги — иначе экспорт терял бы их. Фильмы устроены так же: `movies.csv`
 * и секция `movies` в `extra.json`. Формат `extra.json` читает
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
        // Снимок целиком и в одной транзакции: книги, фильмы и всё, что к ним
        // относится, должны быть из одного состояния БД — иначе манифест
        // разойдётся с содержимым архива.
        val (snapshots, movieSnapshots) = database.withTransaction {
            snapshotBuilder.buildAll() to snapshotBuilder.buildMovies()
        }
        // Подготовка — stat по каждой книге, фильму и каждому фото: это файловый I/O,
        // из `viewModelScope` он шёл бы на главном потоке.
        val prepared = withContext(Dispatchers.IO) { prepare(snapshots, movieSnapshots) }
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
                writeCsv(zip, prepared.books.map { it.book }, prepared.covers.keys)
                zip.closeEntry()

                // movies.csv — те же правила, что у books.csv.
                zip.putNextEntry(ZipEntry("movies.csv"))
                writeMoviesCsv(zip, prepared.movies.map { it.movie }, prepared.covers.keys)
                zip.closeEntry()

                // extra.json — заметки, сессии, теги, фото и чужие поля по id
                // книги и по id фильма.
                zip.putNextEntry(ZipEntry("extra.json"))
                val extra = JSON.encodeToString(ExtraArchive.serializer(), extraArchive(prepared.books, prepared.movies))
                zip.write(extra.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()

                // covers/ — обложки книг и фильмов.
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
    private fun writeCsv(zip: ZipOutputStream, books: List<BookEntity>, coverNames: Set<String>) {
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
                // Обложка ссылается на файл в архиве: если файла нет (потерян
                // с диска), ссылку не пишем — иначе импорт искал бы несуществующий
                // файл, а счётчик обложек врал бы.
                csvField(book.coverPath?.substringAfterLast('/')?.takeIf { it in coverNames }.orEmpty()),
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
     * `movies.csv`: заголовок + строка на фильм.
     *
     * Правила те же, что у `books.csv`: разделитель `;`, кавычки по общему
     * правилу, даты ISO, пустое значение — пустая строка. Заметки, теги, фото
     * и чужие поля фильма — в `extra.json`.
     */
    private fun writeMoviesCsv(zip: ZipOutputStream, movies: List<MovieEntity>, coverNames: Set<String>) {
        zip.write(MOVIES_HEADER.toByteArray(StandardCharsets.UTF_8))
        zip.write("\n".toByteArray(StandardCharsets.UTF_8))
        movies.forEach { movie ->
            val line = listOf(
                csvField(movie.title),
                csvField(movie.originalTitle.orEmpty()),
                csvField(movie.year?.toString().orEmpty()),
                csvField(movie.genre.orEmpty()),
                csvField(movie.nationality.orEmpty()),
                csvField(movie.director.orEmpty()),
                csvField(movie.cast.orEmpty()),
                csvField(movie.durationMin?.toString().orEmpty()),
                csvField(movie.rating?.toString().orEmpty()),
                csvField(epochToDate(movie.watchedAt)),
                csvField(movie.coverPath?.substringAfterLast('/')?.takeIf { it in coverNames }.orEmpty()),
                csvField(movie.plot.orEmpty()),
                csvField(movie.comments.orEmpty()),
                csvField(movie.url.orEmpty()),
                csvField(if (movie.isDownloaded) "1" else ""),
                csvField(movie.source.name),
                csvField(movie.id.toString()),
                csvField(movie.createdAt.toString()),
                csvField(movie.updatedAt.toString()),
            ).joinToString(";")
            zip.write(line.toByteArray(StandardCharsets.UTF_8))
            zip.write("\n".toByteArray(StandardCharsets.UTF_8))
        }
    }

    /**
     * Снимки → то, что попадёт в архив: книги, фильмы, обложки, фото и счётчики.
     */
    private fun prepare(snapshots: List<BookSnapshot>, movieSnapshots: List<MovieSnapshot>): Prepared {
        val books = mutableListOf<BookSnapshot>()
        val movies = mutableListOf<MovieSnapshot>()
        val covers = linkedMapOf<String, File>()
        val photos = linkedMapOf<String, File>()
        var sessions = 0
        var notes = 0
        var quotes = 0
        var reviews = 0
        var tags = 0
        var photoCount = 0
        var missingCovers = 0

        for (snapshot in snapshots) {
            val withFiles = collectFiles(snapshot.book.coverPath, snapshot.photos, covers, photos)
            if (!snapshot.book.coverPath.isNullOrBlank() && !File(snapshot.book.coverPath).isFile) {
                missingCovers++
            }
            books += snapshot.copy(photos = withFiles)
            sessions += snapshot.sessions.size
            notes += snapshot.notes.count { it.kind == NoteKind.NOTE }
            quotes += snapshot.notes.count { it.kind == NoteKind.QUOTE }
            reviews += snapshot.notes.count { it.kind == NoteKind.REVIEW }
            tags += snapshot.tags.size
            photoCount += withFiles.size
        }

        // Фильмы — как книги: обложка рядом с книжными, заметки и теги — общие.
        for (snapshot in movieSnapshots) {
            val withFiles = collectFiles(snapshot.movie.coverPath, snapshot.photos, covers, photos)
            if (!snapshot.movie.coverPath.isNullOrBlank() && !File(snapshot.movie.coverPath).isFile) {
                missingCovers++
            }
            movies += snapshot.copy(photos = withFiles)
            notes += snapshot.notes.count { it.kind == NoteKind.NOTE }
            quotes += snapshot.notes.count { it.kind == NoteKind.QUOTE }
            reviews += snapshot.notes.count { it.kind == NoteKind.REVIEW }
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
            movies = movies.size,
            covers = covers.size,
            coversMissing = missingCovers,
            reviews = reviews,
        )
        return Prepared(books, movies, covers, photos, counts)
    }

    /**
     * Файлы снимка: обложка (по имени файла) и фото (по пути в архиве).
     *
     * Обложка кладётся под именем файла из `cover_path` — импорт ищет её
     * именно по этому имени. Фото без файла в архив не попадает и в
     * `extra.json` не упоминается.
     *
     * @return фото снимка, у которых файл на диске есть.
     */
    private fun collectFiles(
        coverPath: String?,
        photos: List<PhotoSnapshot>,
        covers: MutableMap<String, File>,
        archivePhotos: MutableMap<String, File>,
    ): List<PhotoSnapshot> {
        coverPath?.let { path ->
            val file = File(path)
            if (file.isFile) covers.putIfAbsent(file.name, file)
        }
        val withFiles = photos.filter { File(it.localPath).isFile }
        withFiles.forEach { photo -> archivePhotos.putIfAbsent(photo.archivePath, File(photo.localPath)) }
        return withFiles
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

    /** `extra.json`: содержимое по id книги и по id фильма (строкой). */
    private fun extraArchive(books: List<BookSnapshot>, movies: List<MovieSnapshot>): ExtraArchive =
        ExtraArchive(
            books = books.associate { snapshot ->
                snapshot.book.id.toString() to BookExtras(
                    // Дополнительные даты прочтения: основная — в колонке CSV.
                    dates = snapshot.dates,
                    notes = notesJson(snapshot.notes),
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
                    photos = photosJson(snapshot.photos),
                    extraFields = snapshot.extraFields,
                )
            },
            movies = movies.associate { snapshot ->
                snapshot.movie.id.toString() to MovieExtras(
                    // Дополнительные даты просмотра: основная — в колонке CSV.
                    dates = snapshot.dates,
                    notes = notesJson(snapshot.notes),
                    tags = snapshot.tags,
                    photos = photosJson(snapshot.photos),
                    extraFields = snapshot.extraFields,
                )
            },
        )

    /** Заметки снимка — в формате `extra.json`. */
    private fun notesJson(notes: List<NoteSnapshot>): List<NoteJson> = notes.map { note ->
        NoteJson(
            kind = note.kind.name,
            page = note.page,
            body = note.body,
            createdAt = note.createdAt,
        )
    }

    /** Фото снимка — в формате `extra.json`. */
    private fun photosJson(photos: List<PhotoSnapshot>): List<PhotoJson> = photos.map { photo ->
        PhotoJson(
            file = photo.archivePath,
            noteIndex = photo.noteIndex,
            createdAt = photo.createdAt,
        )
    }

    /** Подготовленный архив: книги, фильмы, обложки, фото и счётчики. */
    private data class Prepared(
        val books: List<BookSnapshot>,
        val movies: List<MovieSnapshot>,
        /** Имя файла в архиве → файл обложки. */
        val covers: Map<String, File>,
        /** Путь в архиве (`photos/{md5}.{ext}`) → файл фото. */
        val photos: Map<String, File>,
        val counts: ExportCounts,
    )

    private fun csvField(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        // `\r` — тоже конец записи для commons-csv: без кавычек строка рвалась
        // и следующая читалась как отдельная запись.
        return if (escaped.contains(';') || escaped.contains('"') ||
            escaped.contains('\n') || escaped.contains('\r')
        ) "\"$escaped\"" else escaped
    }

    private fun epochToDate(epoch: Long?): String {
        if (epoch == null) return ""
        return DateTimeFormatter.ISO_LOCAL_DATE.format(epoch.toDayDate())
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

        /**
         * Заголовок `movies.csv`.
         *
         * Как и у книг, последние две колонки — метки времени фильма в
         * миллисекундах epoch (как их хранит БД): без них импорт ставил бы
         * «создано/изменено» в «сейчас» и архив терял бы историю фильма.
         *
         * `extra_fields` и заметки с тегами и фото — в `extra.json`.
         */
        const val MOVIES_HEADER = "title;original_title;year;genre;nationality;director;cast;duration_min;rating;watched_at;cover_path;plot;comments;url;is_downloaded;source;id;created_at;updated_at"
    }
}

interface AppVersionProvider {
    val appVersion: String
}
