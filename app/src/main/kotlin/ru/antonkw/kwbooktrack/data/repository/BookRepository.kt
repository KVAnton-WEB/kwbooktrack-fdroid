package ru.antonkw.kwbooktrack.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val tagDao: TagDao,
    private val coverStorage: CoverStorage,
    private val photoRepository: PhotoRepository,
) {
    fun observeAll(): Flow<List<BookEntity>> = bookDao.observeAll()

    /** Книга по id потоком — для экрана карточки. */
    fun observeById(id: Long): Flow<BookEntity?> = bookDao.observeById(id)

    /**
     * Все книги разом — для снимка библиотеки.
     *
     * Suspend, а не Flow: Flow-запрос Room выполняет вне транзакции, и снимок
     * собрался бы из разных состояний БД.
     */
    suspend fun getAll(): List<BookEntity> = bookDao.getAll()

    fun observeByStatus(status: BookStatus): Flow<List<BookEntity>> = bookDao.observeByStatus(status)

    fun search(query: String): Flow<List<BookEntity>> {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return bookDao.observeAll()
        val conditions = tokens.joinToString(" AND ") {
            // Every LIKE target is already lowercased on write (withSearchKeys),
            // and the token above is lowercased too, so both sides of the
            // comparison are in the same case. Don't wrap these in LOWER():
            // SQLite folds case for ASCII only, so over Cyrillic it changes
            // nothing — it would read like it works while silently matching
            // nothing.
            "(title_search LIKE ? OR author_search LIKE ?" +
                " OR REPLACE(IFNULL(isbn13, ''), '-', '') LIKE ?" +
                " OR series_search LIKE ?)"
        }
        val sql = "SELECT * FROM books WHERE $conditions ORDER BY updated_at DESC"
        // Bind the same argument 4× per token — one per LIKE position.
        // bindArgs removes the only way a token-derived literal could end up in the
        // SQL string itself.
        val args = tokens.flatMap { token -> List(4) { "%$token%" } }.toTypedArray()
        return bookDao.rawSearch(SimpleSQLiteQuery(sql, args))
    }

    suspend fun findById(id: Long): BookEntity? = bookDao.findById(id)

    suspend fun findByIsbn(isbn13: String): BookEntity? = bookDao.findByIsbn(isbn13)

    suspend fun insert(book: BookEntity): Long {
        val now = System.currentTimeMillis()
        val withTimestamps = book.copy(
            createdAt = if (book.createdAt == 0L) now else book.createdAt,
            updatedAt = now,
        )
        return bookDao.insert(withSearchKeys(withTimestamps))
    }

    suspend fun upsertByIsbn(book: BookEntity): Long =
        bookDao.upsertByIsbn(withSearchKeys(book))

    suspend fun upsertFromImport(book: BookEntity): Long =
        bookDao.upsertFromImport(withSearchKeys(book))

    suspend fun update(book: BookEntity): Int =
        bookDao.update(withSearchKeys(book.copy(updatedAt = System.currentTimeMillis())))

    /**
     * Обновление после импорта: метку «изменено» не трогаем.
     *
     * Обычный [update] ставит `updatedAt = сейчас` — для импорта это неверно:
     * книгу изменили не сейчас, а когда её редактировали в источнике
     * (`mdate` в Tellico, `updated_at` в zip-бэкапе).
     */
    suspend fun updateFromImport(book: BookEntity): Int = bookDao.update(withSearchKeys(book))

    private fun withSearchKeys(book: BookEntity): BookEntity =
        book.copy(
            titleSearch = book.title.lowercase() + " " + (book.subtitle?.lowercase().orEmpty()),
            authorSearch = book.author.lowercase(),
            // Lowercased here rather than in SQL: Kotlin's lowercase() folds
            // Cyrillic, SQLite's lower() does not on AOSP (no ICU build), so a
            // SQL-side LOWER() would silently leave Cyrillic untouched.
            seriesSearch = listOfNotNull(book.series, book.seriesNumber)
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.lowercase() },
        )

    suspend fun delete(book: BookEntity): Int {
        // Delete the row first; if DAO throws, the cover file remains, which is
        // harmless (orphan on disk) compared to a missing cover file with a row
        // still pointing at it.
        // Пути файлов фото читаем ДО удаления строк: после их уже не узнать.
        val photoPaths = photoRepository.observeByMedia(book.id, MediaType.BOOKS).first().map { it.path }
        // Сессии чтения уйдут каскадом — внешний ключ на `books` у них остался.
        // Заметки, фото и связи тегов чистит DAO: внешнего ключа на `books` у
        // них больше нет.
        val rows = bookDao.deleteWithContent(book.id)
        if (rows > 0) {
            photoRepository.deleteFiles(photoPaths)
            coverStorage.delete(book.coverPath)
        }
        return rows
    }

    suspend fun deleteById(id: Long): Int {
        // Путь обложки читаем до удаления строки — после её уже не узнать.
        val coverPath = bookDao.findById(id)?.coverPath
        val photoPaths = photoRepository.observeByMedia(id, MediaType.BOOKS).first().map { it.path }
        val rows = bookDao.deleteWithContent(id)
        if (rows > 0) {
            photoRepository.deleteFiles(photoPaths)
            coverStorage.delete(coverPath)
        }
        return rows
    }

    suspend fun count(): Int = bookDao.count()
}
