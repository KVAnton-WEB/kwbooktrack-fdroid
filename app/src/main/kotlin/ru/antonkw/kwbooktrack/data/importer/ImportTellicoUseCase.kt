package ru.antonkw.kwbooktrack.data.importer

import android.util.Log
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
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
    private val transcoder: CoverTranscoder,
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
        // Confine reads to sourceDir: malicious CSV with "../" segments must not escape it.
        val sourceRoot = runCatching { sourceDir.canonicalPath }.getOrNull() ?: return 0
        val resolved = runCatching { src.canonicalPath }.getOrNull() ?: return 0
        if (!resolved.startsWith(sourceRoot + File.separator) && resolved != sourceRoot) return 0
        if (!src.exists() || !src.isFile) return 0
        val destDir = File(outputDir, "covers").apply { mkdirs() }
        return try {
            // Same funnel as the gallery pick: a 600px lossy WebP is what we
            // keep. Falls back to the original bytes when the source isn't
            // decodable, so a broken cover never fails the import.
            val raw = src.readBytes()
            val webp = transcoder.toWebp(raw)
            val ext = if (webp != null) "webp" else src.extension.ifEmpty { "jpg" }
            val dest = File(destDir, "$bookId.$ext")
            if (webp != null) dest.writeBytes(webp) else Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val existing = bookRepository.findById(bookId)
            if (existing != null) {
                bookRepository.update(existing.copy(coverPath = dest.absolutePath))
            }
            1
        } catch (e: Exception) {
            // Don't swallow OOM / SecurityException silently — surface them
            // so a malicious CSV with a broken cover path doesn't pretend success.
            Log.w(TAG, "cover copy failed for bookId=$bookId src=$src", e)
            0
        }
    }

    private companion object {
        const val TAG = "ImportTellico"
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
