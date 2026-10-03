package ru.antonkw.kwbooktrack.data.exporter

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.io.BufferedOutputStream
import java.io.File
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

@Serializable
data class ExportCounts(
    val books: Int,
    val sessions: Int,
    val notes: Int,
    val quotes: Int,
    val photos: Int,
    val tags: Int,
)

class ExportZipUseCase @Inject constructor(
    private val bookRepository: BookRepository,
    private val noteDao: NoteDao,
    private val sessionDao: ReadingSessionDao,
    private val photoDao: PhotoDao,
    private val tagDao: TagDao,
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
        // Single transaction so the books list and the per-table counts are
        // taken from the same logical snapshot — without this, a write that
        // lands between observeAll().first() and the count() calls makes the
        // manifest disagree with the CSV.
        val snapshot = database.withTransaction {
            val books = bookRepository.observeAll().first()
            Snapshot(
                books = books,
                counts = ExportCounts(
                    books = books.size,
                    sessions = sessionDao.count(),
                    notes = noteDao.countByKind(NoteKind.NOTE),
                    quotes = noteDao.countByKind(NoteKind.QUOTE),
                    photos = photoDao.count(),
                    tags = tagDao.count(),
                ),
            )
        }

        val manifest = ExportManifest(
            appVersion = versionProvider.appVersion,
            exportedAt = System.currentTimeMillis(),
            counts = snapshot.counts,
        )

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            // manifest.json
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSON.encodeToString(ExportManifest.serializer(), manifest).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            // books.csv (UTF-8 без BOM, разделитель `;`)
            zip.putNextEntry(ZipEntry("books.csv"))
            zip.write(BOOKS_HEADER.toByteArray(StandardCharsets.UTF_8))
            zip.write("\n".toByteArray(StandardCharsets.UTF_8))
            snapshot.books.forEach { book ->
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
                    csvField(book.coverPath.orEmpty()),
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
                    csvField(book.printPages?.toString().orEmpty()),
                    csvField(book.originalLanguage.orEmpty()),
                    csvField(book.originalTitle.orEmpty()),
                    csvField(book.annotation.orEmpty()),
                    csvField(if (book.isGift) "1" else ""),
                    csvField(if (book.isLent) "1" else ""),
                    csvField(if (book.isSold) "1" else ""),
                ).joinToString(";")
                zip.write(line.toByteArray(StandardCharsets.UTF_8))
                zip.write("\n".toByteArray(StandardCharsets.UTF_8))
            }
            zip.closeEntry()

            // covers/ — копия обложек, если файл существует
            snapshot.books.forEach { book ->
                val cover = book.coverPath ?: return@forEach
                val file = File(cover)
                if (!file.exists() || !file.isFile) return@forEach
                val entryName = "covers/${book.id}.${file.extension.ifEmpty { "jpg" }}"
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        return manifest
    }

    private data class Snapshot(
        val books: List<ru.antonkw.kwbooktrack.data.db.entity.BookEntity>,
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
        private val JSON = Json { prettyPrint = false }
        const val BOOKS_HEADER = "title;subtitle;author;isbn13;page_count;language;genre;publisher;year;cover_path;rating;status;started_at;finished_at;binding;purchase_place;series;series_number;condition;translator;print_year;print_pages;original_language;original_title;annotation;is_gift;is_lent;is_sold"
    }
}

interface AppVersionProvider {
    val appVersion: String
}
