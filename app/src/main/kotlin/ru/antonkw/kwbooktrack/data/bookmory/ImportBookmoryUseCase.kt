package ru.antonkw.kwbooktrack.data.bookmory

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.text.HtmlToMarkdown
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.io.File
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
                    finishedAt = parsed.sessions.lastOrNull()?.endedAt?.toEpochMs(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    source = MediaSource.BOOKMORY,
                ),
            )
            books++

            // Max page reached per note date — Bookmory's "Журнал чтения" carries
            // session periods but not per-session page numbers, so endPage is
            // reconstructed from notes: the latest page logged during a session's
            // date range is the page the reader was on when the session ended.
            // The last FINISHED session with no notes in range falls back to
            // pageCount: by the time the book is "дочитано", the reader is on
            // the last page. Other sessions without notes stay null.
            val maxPageByDate: Map<java.time.LocalDate, Int> = parsed.notes
                .mapNotNull { note -> note.date?.let { d -> note.page?.let { p -> d to p } } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, pages) -> pages.max() }

            parsed.sessions.forEachIndexed { idx, session ->
                val startedAt = session.startedAt?.toEpochMs()
                val endedAt = session.endedAt?.toEpochMs()
                val endPage: Int? = when {
                    session.startedAt != null && session.endedAt != null -> {
                        var d = session.startedAt
                        val end = session.endedAt
                        var max = 0
                        var found = false
                        while (d != null && !d.isAfter(end)) {
                            val p = maxPageByDate[d]
                            if (p != null && p > max) { max = p; found = true }
                            d = d!!.plusDays(1)
                        }
                        if (found) max else null
                    }
                    else -> null
                } ?: run {
                    val isLastFinishedSession = parsed.status == BookmoryStatus.FINISHED &&
                        idx == parsed.sessions.lastIndex
                    if (isLastFinishedSession) parsed.totalPages else null
                }
                sessionRepository.insert(
                    ReadingSessionEntity(
                        bookId = bookId,
                        startedAt = startedAt ?: System.currentTimeMillis(),
                        endedAt = endedAt,
                        endPage = endPage,
                        durationSec = session.readingTimeMinutes?.let { it * 60L },
                        note = session.comment,
                    ),
                )
                sessions++
            }

            parsed.notes.forEach { note ->
                val createdAt = note.date?.toEpochMs()
                    ?: System.currentTimeMillis()
                noteRepository.insert(
                    NoteEntity(
                        mediaId = bookId,
                        mediaType = MediaType.BOOKS,
                        page = note.page,
                        bodyMd = HtmlToMarkdown.convert(note.content),
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
