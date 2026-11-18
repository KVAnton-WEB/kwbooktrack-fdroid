package ru.antonkw.kwbooktrack.data.snapshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.db.dao.MediaDateDao
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.media.extrasOfType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
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
    private val movieRepository: MovieRepository,
    private val noteDao: NoteDao,
    private val sessionDao: ReadingSessionDao,
    private val photoDao: PhotoDao,
    private val tagDao: TagDao,
    private val mediaDateDao: MediaDateDao,
) {
    suspend fun buildAll(): List<BookSnapshot> {
        // Suspend-запросы, а не Flow: Flow Room выполняет вне транзакции, и снимок
        // собрался бы из разных состояний БД. Вызывающий оборачивает сборку в
        // `withTransaction` — тогда все данные из одного состояния.
        val books = bookRepository.getAll()
        // Заметки и фото принадлежат книге или фильму — для снимка книг
        // берём только книжные.
        val notesByBook = noteDao.getAll()
            .filter { it.mediaType == MediaType.BOOKS }
            .groupBy { it.mediaId }
        val sessionsByBook = sessionDao.getAll().groupBy { it.bookId }
        val photosByBook = photoDao.getAll()
            .filter { it.mediaType == MediaType.BOOKS }
            .groupBy { it.mediaId }
        // Дополнительные даты прочтения — по id книги.
        val extraDatesByBook = extrasOfType(mediaDateDao.getAll(), MediaType.BOOKS)
        // Ключ — пара (id, тип): id книг и id фильмов считаются независимо
        // и могут совпадать, поэтому одного id мало.
        val tagsByOwner = tagDao.tagsForAllMedia().groupBy { it.mediaId to it.mediaType }
        return books.map { book ->
            val notes = notesByBook[book.id].orEmpty().sortedWith(NOTE_ORDER)
            // 1-based индекс заметки в итоговом списке — на него ссылаются фото.
            val noteIndexes = notes.withIndex().associate { (index, note) -> note.id to index + 1 }
            BookSnapshot(
                book = book,
                // Дополнительные даты прочтения: основная — в самой книге.
                dates = extraDatesByBook[book.id].orEmpty(),
                notes = notes.map { it.toSnapshot() },
                sessions = sessionsByBook[book.id].orEmpty().map { it.toSnapshot() },
                tags = tagsByOwner[book.id to MediaType.BOOKS].orEmpty().map { it.name },
                photos = photosByBook[book.id].orEmpty().map { photo ->
                    PhotoSnapshot(
                        // Имя файла в архиве — MD5 его содержимого; считается
                        // отдельным проходом (см. namePhotos), чтобы не читать
                        // файлы внутри транзакции БД.
                        archivePath = "",
                        localPath = photo.path,
                        noteIndex = photo.noteId?.let { noteIndexes[it] },
                        createdAt = photo.createdAt,
                        photoId = photo.id,
                    )
                },
                // Чужие поля Tellico — из колонки books.extra_fields.
                extraFields = book.extraFields,
            )
        }
    }

    /**
     * Снимки фильмов — по тем же правилам, что и книги: заметки, теги и фото
     * общие, различается только владелец — пара (id, тип).
     *
     * Сессий чтения у фильмов нет: трекера страниц у них не бывает.
     */
    suspend fun buildMovies(): List<MovieSnapshot> {
        // Suspend-запросы, а не Flow: Flow-запрос Room выполняет вне транзакции,
        // и снимок собрался бы из разных состояний БД.
        val movies = movieRepository.getAll()
        val notesByMovie = noteDao.getAll()
            .filter { it.mediaType == MediaType.MOVIES }
            .groupBy { it.mediaId }
        val photosByMovie = photoDao.getAll()
            .filter { it.mediaType == MediaType.MOVIES }
            .groupBy { it.mediaId }
        // Дополнительные даты просмотра — по id фильма.
        val extraDatesByMovie = extrasOfType(mediaDateDao.getAll(), MediaType.MOVIES)
        val tagsByOwner = tagDao.tagsForAllMedia().groupBy { it.mediaId to it.mediaType }
        return movies.map { movie ->
            val notes = notesByMovie[movie.id].orEmpty().sortedWith(NOTE_ORDER)
            // 1-based индекс заметки в итоговом списке — на него ссылаются фото.
            val noteIndexes = notes.withIndex().associate { (index, note) -> note.id to index + 1 }
            MovieSnapshot(
                movie = movie,
                // Дополнительные даты просмотра: основная — в самом фильме.
                dates = extraDatesByMovie[movie.id].orEmpty(),
                notes = notes.map { it.toSnapshot() },
                tags = tagsByOwner[movie.id to MediaType.MOVIES].orEmpty().map { it.name },
                photos = photosByMovie[movie.id].orEmpty().map { photo ->
                    PhotoSnapshot(
                        // Как у книг: имя файла считается отдельным проходом.
                        archivePath = "",
                        localPath = photo.path,
                        noteIndex = photo.noteId?.let { noteIndexes[it] },
                        createdAt = photo.createdAt,
                        photoId = photo.id,
                    )
                },
                extraFields = movie.extraFields,
            )
        }
    }

    /**
     * Имена файлов фото в архиве — MD5 от содержимого: устойчиво между
     * экспортом и импортом (повторный импорт не плодит дубли).
     *
     * Отдельный проход, а не часть [buildAll]/[buildMovies]: чтение файлов —
     * блокирующий I/O, и внутри транзакции БД он держал бы блокировку записи
     * на всё время хеширования библиотеки.
     */
    suspend fun namePhotos(
        snapshots: List<BookSnapshot>,
        movieSnapshots: List<MovieSnapshot>,
    ): Pair<List<BookSnapshot>, List<MovieSnapshot>> = withContext(Dispatchers.IO) {
        fun name(photo: PhotoSnapshot): PhotoSnapshot {
            val file = File(photo.localPath)
            val ext = file.extension.ifEmpty { "jpg" }
            val name = if (file.isFile) {
                // Файл мог исчезнуть между проверкой и чтением — откатываемся
                // на id, чтобы экспорт не ронялся.
                runCatching { md5(file.readBytes()) }.getOrNull() ?: photo.photoId.toString()
            } else {
                photo.photoId.toString()
            }
            return photo.copy(archivePath = "photos/$name.$ext")
        }
        snapshots.map { it.copy(photos = it.photos.map(::name)) } to
            movieSnapshots.map { it.copy(photos = it.photos.map(::name)) }
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
