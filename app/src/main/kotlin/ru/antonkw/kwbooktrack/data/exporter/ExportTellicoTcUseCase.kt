package ru.antonkw.kwbooktrack.data.exporter

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.MovieSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import ru.antonkw.kwbooktrack.data.tellico.TcEntry
import ru.antonkw.kwbooktrack.data.tellico.TcMovieEntry
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Экспорт библиотеки в родной формат Tellico: `.tc` = zip с `tellico.xml`,
 * обложками (`images/`) и фото заметок (`photos/`).
 *
 * Имя файла обложки — MD5 от содержимого (как у Tellico): оно устойчиво между
 * экспортом и повторным импортом, поэтому дубли не появляются.
 *
 * В файле **одна** коллекция — того типа, который выбран: Tellico держит в
 * файле одну коллекцию, и при двух он взял бы последнюю и смешал записи.
 */
class ExportTellicoTcUseCase @Inject constructor(
    private val snapshotBuilder: SnapshotBuilder,
    private val database: KwBooktrackDatabase,
    private val versionProvider: AppVersionProvider,
) {

    /** Файловый экспорт — для тестов и прямой записи на диск. */
    suspend operator fun invoke(outputFile: File, mediaType: MediaType = MediaType.BOOKS): ExportManifest =
        invoke(FileOutputStream(outputFile), mediaType)

    /**
     * Экспорт в поток — для UI, где поток открыт SAF-контрактом
     * (`CreateDocument`) и писать надо прямо в него.
     */
    suspend operator fun invoke(
        output: OutputStream,
        mediaType: MediaType = MediaType.BOOKS,
    ): ExportManifest {
        val prepared = database.withTransaction {
            if (mediaType == MediaType.MOVIES) {
                prepareMovies(snapshotBuilder.buildMovies())
            } else {
                prepareBooks(snapshotBuilder.buildAll())
            }
        }
        return withContext(Dispatchers.IO) { writeArchive(prepared, output) }
    }

    private fun writeArchive(prepared: Prepared, output: OutputStream): ExportManifest {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("tellico.xml"))
            zip.write(prepared.xml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            prepared.covers.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry("images/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            prepared.photos.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // manifest.json в .tc не кладём: детектор формата по нему признал бы
        // файл «своим бэкапом» вместо родного Tellico.
        return ExportManifest(
            appVersion = versionProvider.appVersion,
            exportedAt = System.currentTimeMillis(),
            counts = prepared.counts,
        )
    }

    /** Снимки книг → то, что попадёт в архив: записи, обложки с MD5-именами и фото. */
    private fun prepareBooks(snapshots: List<BookSnapshot>): Prepared {
        val entries = mutableListOf<TcEntry>()
        val covers = linkedMapOf<String, File>()
        val photos = linkedMapOf<String, File>()
        var sessions = 0
        var notes = 0
        var quotes = 0
        var tags = 0
        var photoCount = 0

        for (snapshot in snapshots) {
            val coverFile = snapshot.book.coverPath?.let { File(it) }?.takeIf { it.isFile }
            val coverName = coverFile?.let { archiveName(it) }
            if (coverFile != null && coverName != null) covers.putIfAbsent(coverName, coverFile)

            // Фото без файла в архив не попадает и в разметке не упоминается.
            val photoFiles = snapshot.photos.mapNotNull { photo ->
                val file = File(photo.localPath)
                if (file.isFile) photo to file else null
            }
            photoFiles.forEach { (photo, file) -> photos.putIfAbsent(photo.archivePath, file) }

            entries += TcEntry(
                snapshot = snapshot.copy(photos = photoFiles.map { (photo, _) -> photo }),
                coverArchiveName = coverName,
            )
            sessions += snapshot.sessions.size
            notes += snapshot.notes.count { it.kind == NoteKind.NOTE }
            quotes += snapshot.notes.count { it.kind == NoteKind.QUOTE }
            tags += snapshot.tags.size
            photoCount += photoFiles.size
        }

        return Prepared(
            xml = TellicoXmlWriter.write(entries),
            covers = covers,
            photos = photos,
            counts = ExportCounts(
                books = entries.size,
                sessions = sessions,
                notes = notes,
                quotes = quotes,
                photos = photoCount,
                tags = tags,
            ),
        )
    }

    /** Снимки фильмов → то же, но для коллекции `type="3"`. */
    private fun prepareMovies(snapshots: List<MovieSnapshot>): Prepared {
        val entries = mutableListOf<TcMovieEntry>()
        val covers = linkedMapOf<String, File>()
        val photos = linkedMapOf<String, File>()
        var notes = 0
        var quotes = 0
        var tags = 0
        var photoCount = 0

        for (snapshot in snapshots) {
            val coverFile = snapshot.movie.coverPath?.let { File(it) }?.takeIf { it.isFile }
            val coverName = coverFile?.let { archiveName(it) }
            if (coverFile != null && coverName != null) covers.putIfAbsent(coverName, coverFile)

            val photoFiles = snapshot.photos.mapNotNull { photo ->
                val file = File(photo.localPath)
                if (file.isFile) photo to file else null
            }
            photoFiles.forEach { (photo, file) -> photos.putIfAbsent(photo.archivePath, file) }

            entries += TcMovieEntry(
                snapshot = snapshot.copy(photos = photoFiles.map { (photo, _) -> photo }),
                coverArchiveName = coverName,
            )
            notes += snapshot.notes.count { it.kind == NoteKind.NOTE }
            quotes += snapshot.notes.count { it.kind == NoteKind.QUOTE }
            tags += snapshot.tags.size
            photoCount += photoFiles.size
        }

        return Prepared(
            xml = TellicoXmlWriter.writeMovies(entries),
            covers = covers,
            photos = photos,
            counts = ExportCounts(
                books = 0,
                sessions = 0,
                notes = notes,
                quotes = quotes,
                photos = photoCount,
                tags = tags,
                movies = entries.size,
            ),
        )
    }

    /** Имя файла в архиве — MD5 от содержимого. */
    private fun archiveName(file: File): String? {
        val digest = runCatching {
            MessageDigest.getInstance("MD5").digest(file.readBytes())
        }.getOrNull() ?: return null
        val ext = file.extension.ifEmpty { "jpg" }
        return digest.joinToString("") { "%02x".format(it) } + ".$ext"
    }

    /** Подготовленный архив: xml, файлы обложек и фото, счётчики. */
    private data class Prepared(
        val xml: String,
        /** Имя файла в архиве → файл обложки. */
        val covers: Map<String, File>,
        /** Путь в архиве (`photos/{md5}.{ext}`) → файл фото. */
        val photos: Map<String, File>,
        val counts: ExportCounts,
    )
}
