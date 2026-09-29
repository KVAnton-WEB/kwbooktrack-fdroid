package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import java.io.File
import java.io.Reader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

data class ImportTellicoResult(
    val total: Int,
    val imported: Int,
    val skipped: Int,
    val coversCopied: Int,
    val notesImported: Int,
    val dryRun: Boolean,
)

data class ImportTellicoOptions(
    val coverSourceDir: File? = null,
    val coverOutputDir: File? = null,
    val dryRun: Boolean = false,
)

class ImportTellicoUseCase @Inject constructor(
    private val csvReader: TellicoCsvReader,
    private val mapper: TellicoBookMapper,
    private val bookRepository: BookRepository,
    private val noteRepository: NoteRepository,
) {
    suspend operator fun invoke(
        input: Reader,
        options: ImportTellicoOptions = ImportTellicoOptions(),
    ): ImportTellicoResult {
        val records = csvReader.read(input)
        var imported = 0
        var skipped = 0
        var coversCopied = 0
        var notesImported = 0

        records.forEach { record ->
            val mapped = mapper.map(record)
            if (mapped == null) {
                skipped++
                return@forEach
            }
            if (options.dryRun) {
                imported++
                return@forEach
            }
            val bookId = bookRepository.upsertFromImport(mapped.book)
            imported++
            coversCopied += persistCover(options, bookId, mapped.book.coverPath)
            notesImported += persistNotes(bookId, mapped.quotes, mapped.review)
        }
        return ImportTellicoResult(
            total = records.size,
            imported = imported,
            skipped = skipped,
            coversCopied = coversCopied,
            notesImported = notesImported,
            dryRun = options.dryRun,
        )
    }

    private suspend fun persistCover(options: ImportTellicoOptions, bookId: Long, coverRel: String?): Int {
        if (options.dryRun || coverRel.isNullOrBlank()) return 0
        val sourceDir = options.coverSourceDir ?: return 0
        val outputDir = options.coverOutputDir ?: return 0
        val src = File(sourceDir, coverRel)
        if (!src.exists() || !src.isFile) return 0
        val ext = src.extension.ifEmpty { "jpg" }
        val destDir = File(outputDir, "covers").apply { mkdirs() }
        val dest = File(destDir, "$bookId.$ext")
        return try {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val existing = bookRepository.findById(bookId)
            if (existing != null) {
                bookRepository.update(existing.copy(coverPath = dest.absolutePath))
            }
            1
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun persistNotes(
        bookId: Long,
        quotes: List<String>,
        review: String?,
    ): Int {
        val now = System.currentTimeMillis()
        var count = 0
        quotes.forEach { q ->
            val id = noteRepository.insert(
                NoteEntity(
                    id = 0L,
                    bookId = bookId,
                    page = null,
                    bodyMd = q,
                    kind = NoteKind.QUOTE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (id > 0L) count++
        }
        if (review != null) {
            val id = noteRepository.insert(
                NoteEntity(
                    id = 0L,
                    bookId = bookId,
                    page = null,
                    bodyMd = review,
                    kind = NoteKind.REVIEW,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (id > 0L) count++
        }
        return count
    }
}
