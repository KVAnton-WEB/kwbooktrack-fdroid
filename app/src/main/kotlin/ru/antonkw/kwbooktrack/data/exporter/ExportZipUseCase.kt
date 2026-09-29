package ru.antonkw.kwbooktrack.data.exporter

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
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
) {
    suspend operator fun invoke(outputFile: File): ExportManifest {
        val allBooks = bookRepository.observeAll().first()

        val counts = ExportCounts(
            books = allBooks.size,
            sessions = sessionDao.count(),
            notes = noteDao.countByKind(NoteKind.NOTE),
            quotes = noteDao.countByKind(NoteKind.QUOTE),
            photos = photoDao.count(),
            tags = tagDao.count(),
        )
        val manifest = ExportManifest(
            appVersion = versionProvider.appVersion,
            exportedAt = System.currentTimeMillis(),
            counts = counts,
        )

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
            // manifest.json
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSON.encodeToString(ExportManifest.serializer(), manifest).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            // books.csv (UTF-8 без BOM, разделитель `;`)
            zip.putNextEntry(ZipEntry("books.csv"))
            zip.write(BOOKS_HEADER.toByteArray(StandardCharsets.UTF_8))
            zip.write("\n".toByteArray(StandardCharsets.UTF_8))
            allBooks.forEach { book ->
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
                ).joinToString(";")
                zip.write(line.toByteArray(StandardCharsets.UTF_8))
                zip.write("\n".toByteArray(StandardCharsets.UTF_8))
            }
            zip.closeEntry()

            // covers/ — копия обложек, если файл существует
            allBooks.forEach { book ->
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

    private fun csvField(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(';') || escaped.contains('"') || escaped.contains('\n')) "\"$escaped\"" else escaped
    }

    private fun epochToDate(epoch: Long?): String {
        if (epoch == null) return ""
        return DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }

    companion object {
        private val JSON = Json { prettyPrint = false }
        const val BOOKS_HEADER = "title;subtitle;author;isbn13;page_count;language;genre;publisher;year;cover_path;rating;status;started_at;finished_at"
    }
}

interface AppVersionProvider {
    val appVersion: String
}
