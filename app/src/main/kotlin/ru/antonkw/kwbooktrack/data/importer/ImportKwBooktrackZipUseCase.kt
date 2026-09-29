package ru.antonkw.kwbooktrack.data.importer

import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.exporter.ExportManifest
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Импорт ZIP-экспорта, сгенерированного [ExportZipUseCase].
 *
 * Читает `manifest.json`, `books.csv` и файлы обложек из каталога `covers/`,
 * затем вставляет записи через `BookRepository.upsertFromImport`, чтобы не дублировать
 * существующие книги (по ISBN или по связке title+author).
 * Сессии/заметки/цитаты/фото/теги в текущем формате экспорта не пишутся,
 * поэтому [ImportResult] отражает только то, что фактически восстановлено.
 */
class ImportKwBooktrackZipUseCase @Inject constructor(
    private val bookRepository: BookRepository,
    private val coverStorage: CoverStorage,
) {
    suspend operator fun invoke(zipFile: File): ImportResult {
        var manifest: ExportManifest? = null
        var booksCsv: String? = null
        val coverBytes = mutableMapOf<String, ByteArray>()

        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                when {
                    e.name == "manifest.json" -> {
                        manifest = Json.decodeFromString(
                            ExportManifest.serializer(),
                            zin.readBytes().toString(StandardCharsets.UTF_8),
                        )
                    }
                    e.name == "books.csv" -> {
                        booksCsv = zin.readBytes().toString(StandardCharsets.UTF_8)
                    }
                    e.name.startsWith("covers/") -> {
                        coverBytes[e.name.removePrefix("covers/")] = zin.readBytes()
                    }
                }
                e = zin.nextEntry
            }
        }

        val parsedManifest = manifest
            ?: return ImportResult(0, 0, IllegalStateException("manifest.json не найден в ZIP"))
        if (parsedManifest.source != "kwbooktrack") {
            return ImportResult(
                0,
                0,
                IllegalStateException("ZIP не является экспортом kwbooktrack (source=${parsedManifest.source})"),
            )
        }
        val csv = booksCsv
            ?: return ImportResult(0, 0, IllegalStateException("books.csv не найден в ZIP"))

        val format = CSVFormat.Builder.create()
            .setDelimiter(';')
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .build()

        val records = CSVParser(StringReader(csv), format).use { it.records }

        var imported = 0
        var skipped = 0
        var lastError: Throwable? = null

        records.forEachIndexed { _, record ->
            try {
                val title = record.get("title").orEmpty().trim()
                if (title.isEmpty()) {
                    skipped++
                    return@forEachIndexed
                }
                val book = BookEntity(
                    title = title,
                    subtitle = record.get("subtitle").orEmpty().ifBlank { null },
                    author = record.get("author").orEmpty().ifBlank { "(автор неизвестен)" },
                    isbn13 = record.get("isbn13").orEmpty().ifBlank { null },
                    pageCount = record.get("page_count").orEmpty().toIntOrNull(),
                    language = record.get("language").orEmpty().ifBlank { "ru" },
                    genre = record.get("genre").orEmpty().ifBlank { null },
                    publisher = record.get("publisher").orEmpty().ifBlank { null },
                    year = record.get("year").orEmpty().toIntOrNull(),
                    rating = record.get("rating").orEmpty().toIntOrNull(),
                    status = record.get("status").orEmpty()
                        .let { runCatching { BookStatus.valueOf(it) }.getOrDefault(BookStatus.WISHLIST) },
                    startedAt = parseDate(record.get("started_at")),
                    finishedAt = parseDate(record.get("finished_at")),
                    source = BookSource.MANUAL,
                    coverPath = record.get("cover_path").orEmpty().ifBlank { null },
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                val newId = bookRepository.upsertFromImport(book)
                imported++

                // Восстанавливаем обложку, если она была в архиве.
                val coverEntry = coverBytes.entries.firstOrNull { entry ->
                    entry.key.startsWith("$newId.")
                }
                if (coverEntry != null) {
                    val ext = coverEntry.key.substringAfterLast('.', "jpg")
                    val tempPath = coverStorage.saveTemp(coverEntry.value.inputStream(), ext)
                    val finalPath = coverStorage.promoteToBook(newId, tempPath)
                    if (finalPath != null) {
                        val updated = book.copy(id = newId, coverPath = finalPath)
                        bookRepository.update(updated)
                    }
                }
            } catch (e: Exception) {
                lastError = e
                skipped++
            }
        }

        return ImportResult(imported = imported, skipped = skipped, error = lastError)
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val error: Throwable?,
) {
    val isSuccess: Boolean get() = error == null
}