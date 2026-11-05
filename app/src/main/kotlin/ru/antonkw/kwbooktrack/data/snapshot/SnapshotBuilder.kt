package ru.antonkw.kwbooktrack.data.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Собирает снимок библиотеки из БД — точку отсчёта для обоих форматов
 * (родной Tellico `.tc` и zip-экспорт).
 */
@Singleton
class SnapshotBuilder @Inject constructor(
    private val bookRepository: BookRepository,
    private val noteDao: NoteDao,
    private val sessionDao: ReadingSessionDao,
    private val photoDao: PhotoDao,
    private val tagDao: TagDao,
) {
    suspend fun buildAll(): List<BookSnapshot> {
        val books = bookRepository.observeAll().first()
        val notesByBook = noteDao.observeAll().first().groupBy { it.bookId }
        val sessionsByBook = sessionDao.observeAll().first().groupBy { it.bookId }
        val photosByBook = photoDao.observeAll().first().groupBy { it.bookId }
        return books.map { book ->
            val notes = notesByBook[book.id].orEmpty().sortedWith(NOTE_ORDER)
            // 1-based индекс заметки в итоговом списке — на него ссылаются фото.
            val noteIndexes = notes.withIndex().associate { (index, note) -> note.id to index + 1 }
            BookSnapshot(
                book = book,
                notes = notes.map { it.toSnapshot() },
                sessions = sessionsByBook[book.id].orEmpty().map { it.toSnapshot() },
                tags = tagDao.tagsForBook(book.id).map { it.name },
                photos = photosByBook[book.id].orEmpty().map { photo ->
                    PhotoSnapshot(
                        archivePath = photo.archivePath(),
                        localPath = photo.path,
                        noteIndex = photo.noteId?.let { noteIndexes[it] },
                        createdAt = photo.createdAt,
                    )
                },
                // Колонка books.extra_fields появится в следующей задаче —
                // до неё чужих полей у книги нет.
                extraFields = emptyMap(),
            )
        }
    }

    /**
     * Имя файла фото в архиве — MD5 от содержимого: устойчиво между экспортом
     * и импортом (повторный импорт не плодит дубли). Файла нет или он нечитаем —
     * откатываемся на id, чтобы экспорт не ронялся.
     */
    private suspend fun PhotoEntity.archivePath(): String {
        val file = File(path)
        val ext = file.extension.ifEmpty { "jpg" }
        val name = if (file.isFile) {
            // Чтение файла — блокирующий I/O, поэтому уводим его с вызывающего
            // потока (вызов может прийти из viewModelScope, то есть с Main).
            // Файл мог исчезнуть или оказаться нечитаемым между проверкой и
            // чтением — тогда откатываемся на id.
            val digest = withContext(Dispatchers.IO) {
                runCatching { md5(file.readBytes()) }.getOrNull()
            }
            digest ?: id.toString()
        } else {
            id.toString()
        }
        return "photos/$name.$ext"
    }

    private fun NoteEntity.toSnapshot() = NoteSnapshot(
        kind = kind,
        body = bodyMd,
        page = page,
        createdAt = createdAt,
    )

    private fun ReadingSessionEntity.toSnapshot() = SessionSnapshot(
        startedAt = startedAt,
        endedAt = endedAt,
        startPage = startPage,
        endPage = endPage,
        durationSec = durationSec,
        note = note,
    )

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Ранг вида: цитата, рецензия, заметка — как их показывает Tellico. */
        fun kindRank(kind: NoteKind): Int = when (kind) {
            NoteKind.QUOTE -> 0
            NoteKind.REVIEW -> 1
            NoteKind.NOTE -> 2
        }

        val NOTE_ORDER = compareBy<NoteEntity>(
            { kindRank(it.kind) },
            { it.createdAt },
            { it.id },
        )
    }
}
