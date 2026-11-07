package ru.antonkw.kwbooktrack.data.snapshot

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Что добавил [SnapshotApplier.apply].
 *
 * [tags] — привязок тегов к книге (не новых тегов): повторный импорт
 * привязывает тег снова, но кросс-ссылка в БД одна и та же.
 */
data class ApplyStats(
    val books: Int = 0,
    val notes: Int = 0,
    val sessions: Int = 0,
    val tags: Int = 0,
    val photos: Int = 0,
    val covers: Int = 0,
)

/**
 * Вливает снимок книги в БД по правилам «слияние без дублей»: новое
 * добавляется, существующее не трогается, повторный импорт не плодит дубли.
 *
 * Совпадение: заметка — по (`kind`, `body`, `page`), сессия — по `startedAt`,
 * фото — по локальному пути файла, тег — по имени.
 */
class SnapshotApplier @Inject constructor(
    private val bookRepository: BookRepository,
    private val noteRepository: NoteRepository,
    private val sessionRepository: SessionRepository,
    private val tagRepository: TagRepository,
    private val photoRepository: PhotoRepository,
    private val photoStorage: PhotoStorage,
    private val transcoder: CoverTranscoder,
) {

    suspend fun apply(snapshot: BookSnapshot, assets: ImportAssets): ApplyStats {
        val now = System.currentTimeMillis()
        // Имя обложки из архива — не локальный путь, в БД ему не место.
        // id снимка — тоже: снимок не привязан к строкам БД, а книга ищется
        // по ISBN или связке title+author (upsertFromImport).
        val book = snapshot.book.copy(
            id = 0L,
            coverPath = null,
            extraFields = snapshot.book.extraFields + snapshot.extraFields,
        )
        val bookId = bookRepository.upsertFromImport(book)

        val covers = applyCover(snapshot, bookId, assets)
        val notes = applyNotes(snapshot, bookId, now)
        val sessions = applySessions(snapshot, bookId)
        val tags = applyTags(snapshot, bookId)
        val photos = applyPhotos(snapshot, bookId, notes.ids, assets, now)

        return ApplyStats(
            books = 1,
            notes = notes.added,
            sessions = sessions,
            tags = tags,
            photos = photos,
            covers = covers,
        )
    }

    /**
     * Обложка: файл из архива → WebP → `<coverOutputDir>/covers/{bookId}.{ext}`.
     * В БД кладётся локальный путь, а не имя файла из архива.
     */
    private suspend fun applyCover(snapshot: BookSnapshot, bookId: Long, assets: ImportAssets): Int {
        val archiveName = snapshot.book.coverPath
        if (archiveName.isNullOrBlank()) return 0
        val src = assets.find(archiveName) ?: return 0
        val destDir = assets.coverOutputDir?.let { File(it, "covers").apply { mkdirs() } } ?: return 0
        return try {
            // Та же воронка, что у обложки из галереи: 600px lossy WebP.
            // Не декодируется — оставляем исходник, обложку терять нельзя.
            val raw = src.readBytes()
            val webp = transcoder.toWebp(raw)
            val ext = if (webp != null) "webp" else src.extension.ifEmpty { "jpg" }
            val dest = File(destDir, "$bookId.$ext")
            dest.writeBytes(webp ?: raw)
            // Обновляем строку книги, а не снимок: что выигрывает при слиянии,
            // уже решил upsertFromImport. Метку «изменено» не трогаем — книгу
            // изменили не сейчас, а в источнике.
            val existing = bookRepository.findById(bookId) ?: return 0
            bookRepository.updateFromImport(existing.copy(coverPath = dest.absolutePath))
            1
        } catch (e: CancellationException) {
            // Отмена импорта — не «плохая обложка»: пробрасываем дальше.
            throw e
        } catch (e: Exception) {
            // Плохая обложка не должна ронять импорт — как в остальных импортёрах.
            Log.w(TAG, "cover copy failed for bookId=$bookId src=$src", e)
            0
        }
    }

    /**
     * Заметки: вставляются в порядке снимка (их id в БД растут по порядку),
     * уже существующие не трогаются. Возвращает id заметок снимка по порядку —
     * на них ссылаются фото ([PhotoSnapshot.noteIndex]).
     */
    private suspend fun applyNotes(snapshot: BookSnapshot, bookId: Long, now: Long): NotesApplied {
        val existing = noteRepository.observeByBook(bookId).first().toMutableList()
        val ids = ArrayList<Long>(snapshot.notes.size)
        var added = 0
        for (note in snapshot.notes) {
            val match = existing.firstOrNull {
                it.kind == note.kind && it.bodyMd == note.body && it.page == note.page
            }
            if (match != null) {
                ids += match.id
                continue
            }
            val entity = NoteEntity(
                bookId = bookId,
                page = note.page,
                bodyMd = note.body,
                kind = note.kind,
                createdAt = note.createdAt ?: now,
                updatedAt = now,
            )
            val id = noteRepository.insert(entity)
            existing += entity.copy(id = id)
            ids += id
            added++
        }
        return NotesApplied(ids, added)
    }

    /** Сессии: совпадение по времени начала. */
    private suspend fun applySessions(snapshot: BookSnapshot, bookId: Long): Int {
        val starts = sessionRepository.observeByBook(bookId).first()
            .map { it.startedAt }
            .toMutableSet()
        var added = 0
        for (session in snapshot.sessions) {
            if (!starts.add(session.startedAt)) continue
            sessionRepository.insert(
                ReadingSessionEntity(
                    bookId = bookId,
                    startedAt = session.startedAt,
                    endedAt = session.endedAt,
                    startPage = session.startPage,
                    endPage = session.endPage,
                    durationSec = session.durationSec,
                    note = session.note,
                ),
            )
            added++
        }
        return added
    }

    /** Теги: findOrCreate + attachToBook — кросс-ссылка уникальна, дублей нет. */
    private suspend fun applyTags(snapshot: BookSnapshot, bookId: Long): Int {
        var added = 0
        for (name in snapshot.tags) {
            val tagId = tagRepository.findOrCreate(name)
            // findOrCreate отдаёт -1 на пустом имени — такого тега нет.
            if (tagId <= 0L) continue
            tagRepository.attachToBook(bookId, tagId)
            added++
        }
        return added
    }

    /**
     * Фото: файл из архива → локальный файл `{md5}_{bookId}.{ext}` → строка в БД.
     * Имя в архиве — MD5 от содержимого, поэтому повторный импорт узнаёт
     * уже сохранённое фото и не плодит дубли.
     */
    private suspend fun applyPhotos(
        snapshot: BookSnapshot,
        bookId: Long,
        noteIds: List<Long>,
        assets: ImportAssets,
        now: Long,
    ): Int {
        val paths = photoRepository.observeByBook(bookId).first().map { it.path }.toMutableSet()
        var added = 0
        for (photo in snapshot.photos) {
            val src = assets.find(photo.archivePath) ?: continue
            val archive = File(photo.archivePath)
            val ext = archive.extension.ifEmpty { "jpg" }
            val bytes = try {
                src.readBytes()
            } catch (e: IOException) {
                Log.w(TAG, "photo read failed for bookId=$bookId src=$src", e)
                continue
            }
            val path = photoStorage.saveNamed("${archive.nameWithoutExtension}_$bookId", bytes.inputStream(), ext)
            if (!paths.add(path)) continue
            photoRepository.insert(
                PhotoEntity(
                    bookId = bookId,
                    // noteIndex — 1-based индекс заметки в снимке.
                    noteId = photo.noteIndex?.let { noteIds.getOrNull(it - 1) },
                    path = path,
                    createdAt = photo.createdAt ?: now,
                ),
            )
            added++
        }
        return added
    }

    /** Id заметок снимка в БД (по порядку) и сколько из них вставлено. */
    private class NotesApplied(val ids: List<Long>, val added: Int)

    private companion object {
        const val TAG = "SnapshotApplier"
    }
}
