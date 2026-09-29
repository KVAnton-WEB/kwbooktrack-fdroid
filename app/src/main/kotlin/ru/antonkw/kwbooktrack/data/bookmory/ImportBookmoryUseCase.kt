package ru.antonkw.kwbooktrack.data.bookmory

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import java.io.File
import java.time.ZoneOffset
import javax.inject.Inject

data class BookmoryImportResult(
    val totalFiles: Int,
    val importedBooks: Int,
    val importedSessions: Int,
    val importedNotes: Int,
    val skipped: Int,
)

class ImportBookmoryUseCase @Inject constructor(
    private val parser: BookmoryParser,
    private val bookRepository: BookRepository,
    private val sessionRepository: SessionRepository,
    private val noteRepository: NoteRepository,
) {
    suspend operator fun invoke(directory: File): BookmoryImportResult {
        val files = directory.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
            ?: return BookmoryImportResult(0, 0, 0, 0, 0)

        var books = 0
        var sessions = 0
        var notes = 0
        var skipped = 0

        files.forEach { file ->
            val parsed = parser.parse(file.readText())
            if (parsed == null) {
                skipped++
                return@forEach
            }

            val bookId = bookRepository.upsertFromImport(
                BookEntity(
                    title = parsed.title,
                    author = parsed.authors.joinToString(", ").ifEmpty { "(автор неизвестен)" },
                    isbn13 = parsed.isbn,
                    pageCount = parsed.totalPages,
                    language = parsed.language?.take(2) ?: "ru",
                    publisher = parsed.publisher,
                    year = parsed.publicationDate?.toIntOrNull(),
                    rating = parsed.sessions.lastOrNull()?.rating?.toInt()?.coerceIn(1, 5),
                    status = when (parsed.status) {
                        BookmoryStatus.FINISHED -> BookStatus.FINISHED
                        BookmoryStatus.READING -> BookStatus.READING
                        BookmoryStatus.WISHLIST -> BookStatus.WISHLIST
                        BookmoryStatus.UNKNOWN -> BookStatus.WISHLIST
                    },
                    finishedAt = parsed.sessions.lastOrNull()?.endedAt?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    source = BookSource.BOOKMORY,
                ),
            )
            books++

            parsed.sessions.forEach { session ->
                val startedAt = session.startedAt?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                val endedAt = session.endedAt?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                sessionRepository.insert(
                    ReadingSessionEntity(
                        bookId = bookId,
                        startedAt = startedAt ?: System.currentTimeMillis(),
                        endedAt = endedAt,
                        durationSec = session.readingTimeMinutes?.let { it * 60L },
                        note = session.comment,
                    ),
                )
                sessions++
            }

            parsed.notes.forEach { note ->
                val createdAt = note.date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                    ?: System.currentTimeMillis()
                noteRepository.insert(
                    NoteEntity(
                        bookId = bookId,
                        page = note.page,
                        bodyMd = note.content,
                        kind = NoteKind.NOTE,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                    ),
                )
                notes++
            }
        }

        return BookmoryImportResult(
            totalFiles = files.size,
            importedBooks = books,
            importedSessions = sessions,
            importedNotes = notes,
            skipped = skipped,
        )
    }
}
