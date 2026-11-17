package ru.antonkw.kwbooktrack.data.snapshot

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Что добавил [SnapshotApplier.apply].
 *
 * [tags] — привязок тегов к книге (не новых тегов): повторный импорт
 * привязывает тег снова, но кросс-ссылка в БД одна и та же.
 */
data class ApplyStats(
    val books: Int = 0,
    val movies: Int = 0,
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
    private val movieRepository: MovieRepository,
    private val noteRepository: NoteRepository,
    private val sessionRepository: SessionRepository,
    private val tagRepository: TagRepository,
    private val photoRepository: PhotoRepository,
    private val mediaDateRepository: MediaDateRepository,
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
        // Даты прочтения: архив приносит основную (в CSV) и дополнительные.
        // Берём основную из СЛИТОЙ строки, а не из архива: в архиве её может
        // не быть, а в базе — быть, и тогда дата потерялась бы.
        val row = bookRepository.findById(bookId)
        val primary = applyDates(row?.finishedAt, snapshot.dates, bookId, MediaType.BOOKS)
        if (primary != row?.finishedAt) {
            row?.let { bookRepository.updateFromImport(it.copy(finishedAt = primary)) }
        }

        val covers = applyBookCover(snapshot, bookId, assets)
        val notes = applyNotes(snapshot.notes, bookId, MediaType.BOOKS, now)
        val sessions = applySessions(snapshot.sessions, bookId)
        val tags = applyTags(snapshot.tags, bookId, MediaType.BOOKS)
        val photos = applyPhotos(snapshot.photos, bookId, MediaType.BOOKS, notes.ids, assets, now)

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
     * Фильм применяется теми же правилами, что и книга: заметки, теги и фото
     * общие, различается только владелец — пара (id, тип).
     *
     * Сессий чтения у фильма нет: трекера страниц у него не бывает.
     */
    suspend fun apply(snapshot: MovieSnapshot, assets: ImportAssets): ApplyStats {
        val now = System.currentTimeMillis()
        val movie = snapshot.movie.copy(
            id = 0L,
            coverPath = null,
            extraFields = snapshot.movie.extraFields + snapshot.extraFields,
        )
        val movieId = movieRepository.upsertFromImport(movie)
        // Даты просмотра: архив приносит основную (в CSV) и дополнительные.
        // Основная — из слитой строки: в архиве её может не быть, а в базе
        // быть, и тогда дата потерялась бы.
        val row = movieRepository.findById(movieId)
        val primary = applyDates(row?.watchedAt, snapshot.dates, movieId, MediaType.MOVIES)
        if (primary != row?.watchedAt) {
            row?.let { movieRepository.updateFromImport(it.copy(watchedAt = primary)) }
        }

        val covers = applyMovieCover(snapshot, movieId, assets)
        val notes = applyNotes(snapshot.notes, movieId, MediaType.MOVIES, now)
        val tags = applyTags(snapshot.tags, movieId, MediaType.MOVIES)
        val photos = applyPhotos(snapshot.photos, movieId, MediaType.MOVIES, notes.ids, assets, now)

        return ApplyStats(
            movies = 1,
            notes = notes.added,
            tags = tags,
            photos = photos,
            covers = covers,
        )
    }

    /**
     * Даты владельца: основная (из колонки записи) и дополнительные (из
     * архива) объединяются с тем, что уже есть в базе.
     *
     * Основной остаётся самая ранняя — из неё Tellico берёт первую дату.
     * Возвращает новую основную дату.
     */
    private suspend fun applyDates(
        primary: Long?,
        extra: List<Long>,
        mediaId: Long,
        mediaType: MediaType,
    ): Long? {
        val existing = mediaDateRepository.getFor(mediaId, mediaType)
        val dates = (listOfNotNull(primary) + extra + existing).distinct().sorted()
        mediaDateRepository.replaceFor(mediaId, mediaType, dates.drop(1))
        return dates.firstOrNull()
    }

    /**
     * Обложка: файл из архива → WebP → `<coverOutputDir>/covers/cover_{uuid}.{ext}`.
     * В БД кладётся локальный путь, а не имя файла из архива.
     *
     * Имя — UUID, а не id: файл не привязан к строке БД, и обложка фильма не
     * перезапишет обложку книги с тем же id.
     */
    private suspend fun applyBookCover(snapshot: BookSnapshot, bookId: Long, assets: ImportAssets): Int {
        val dest = copyCover(snapshot.book.coverPath, assets) ?: return 0
        val existing = bookRepository.findById(bookId) ?: return 0
        bookRepository.updateFromImport(existing.copy(coverPath = dest.absolutePath))
        dropPreviousCover(existing.coverPath, dest.absolutePath)
        return 1
    }

    private suspend fun applyMovieCover(snapshot: MovieSnapshot, movieId: Long, assets: ImportAssets): Int {
        val dest = copyCover(snapshot.movie.coverPath, assets) ?: return 0
        val existing = movieRepository.findById(movieId) ?: return 0
        movieRepository.updateFromImport(existing.copy(coverPath = dest.absolutePath))
        dropPreviousCover(existing.coverPath, dest.absolutePath)
        return 1
    }

    /**
     * Файл обложки из архива → локальный файл.
     *
     * Та же воронка, что у обложки из галереи: 600px lossy WebP. Не
     * декодируется — оставляем исходник, обложку терять нельзя.
     */
    private suspend fun copyCover(archiveName: String?, assets: ImportAssets): File? {
        if (archiveName.isNullOrBlank()) return null
        val src = assets.find(archiveName) ?: return null
        val destDir = assets.coverOutputDir?.let { File(it, "covers").apply { mkdirs() } } ?: return null
        return try {
            val raw = src.readBytes()
            val webp = transcoder.toWebp(raw)
            val ext = if (webp != null) "webp" else src.extension.ifEmpty { "jpg" }
            val dest = File(destDir, "cover_${UUID.randomUUID()}.$ext")
            dest.writeBytes(webp ?: raw)
            dest
        } catch (e: CancellationException) {
            // Отмена импорта — не «плохая обложка»: пробрасываем дальше.
            throw e
        } catch (e: Exception) {
            // Плохая обложка не должна ронять импорт — как в остальных импортёрах.
            Log.w(TAG, "cover copy failed for src=$src", e)
            null
        }
    }

    /**
     * Прежняя обложка больше не нужна: имя новое, и без удаления старый файл
     * остался бы на диске навсегда.
     */
    private fun dropPreviousCover(previous: String?, current: String) {
        previous
            ?.takeIf { it != current }
            ?.let { runCatching { File(it).delete() } }
    }

    /**
     * Заметки: вставляются в порядке снимка (их id в БД растут по порядку),
     * уже существующие не трогаются. Возвращает id заметок снимка по порядку —
     * на них ссылаются фото ([PhotoSnapshot.noteIndex]).
     */
    private suspend fun applyNotes(
        notes: List<NoteSnapshot>,
        mediaId: Long,
        mediaType: MediaType,
        now: Long,
    ): NotesApplied {
        val existing = noteRepository.observeByMedia(mediaId, mediaType).first().toMutableList()
        val ids = ArrayList<Long>(notes.size)
        var added = 0
        for (note in notes) {
            val match = existing.firstOrNull {
                it.kind == note.kind && it.bodyMd == note.body && it.page == note.page
            }
            if (match != null) {
                ids += match.id
                continue
            }
            val entity = NoteEntity(
                mediaId = mediaId,
                mediaType = mediaType,
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

    /** Сессии: совпадение по времени начала. Только у книг. */
    private suspend fun applySessions(sessions: List<SessionSnapshot>, bookId: Long): Int {
        val starts = sessionRepository.observeByBook(bookId).first()
            .map { it.startedAt }
            .toMutableSet()
        var added = 0
        for (session in sessions) {
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

    /** Теги: findOrCreate + привязка — кросс-ссылка уникальна, дублей нет. */
    private suspend fun applyTags(tags: List<String>, mediaId: Long, mediaType: MediaType): Int {
        var added = 0
        for (name in tags) {
            val tagId = tagRepository.findOrCreate(name)
            // findOrCreate отдаёт -1 на пустом имени — такого тега нет.
            if (tagId <= 0L) continue
            tagRepository.attachToMedia(mediaId, mediaType, tagId)
            added++
        }
        return added
    }

    /**
     * Фото: файл из архива → локальный файл `{md5}_{тип}_{id}.{ext}` → строка в БД.
     * Имя в архиве — MD5 от содержимого, поэтому повторный импорт узнаёт
     * уже сохранённое фото и не плодит дубли.
     */
    private suspend fun applyPhotos(
        photos: List<PhotoSnapshot>,
        mediaId: Long,
        mediaType: MediaType,
        noteIds: List<Long>,
        assets: ImportAssets,
        now: Long,
    ): Int {
        val paths = photoRepository.observeByMedia(mediaId, mediaType).first().map { it.path }.toMutableSet()
        var added = 0
        for (photo in photos) {
            val src = assets.find(photo.archivePath) ?: continue
            val archive = File(photo.archivePath)
            val ext = archive.extension.ifEmpty { "jpg" }
            val bytes = try {
                src.readBytes()
            } catch (e: IOException) {
                Log.w(TAG, "photo read failed for mediaId=$mediaId src=$src", e)
                continue
            }
            // Тип носителя в имени: id книг и id фильмов считаются независимо
            // и могут совпасть, а одинаковое содержимое даёт одинаковый md5 —
            // без типа два разных файла получили бы одно имя.
            val path = photoStorage.saveNamed(
                "${archive.nameWithoutExtension}_${mediaType.name.lowercase()}_$mediaId",
                bytes.inputStream(),
                ext,
            )
            if (!paths.add(path)) continue
            photoRepository.insert(
                PhotoEntity(
                    mediaId = mediaId,
                    mediaType = mediaType,
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
