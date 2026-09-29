package ru.antonkw.kwbooktrack.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val tagDao: TagDao,
    private val coverStorage: CoverStorage,
) {
    fun observeAll(): Flow<List<BookEntity>> = bookDao.observeAll()

    fun observeByStatus(status: BookStatus): Flow<List<BookEntity>> = bookDao.observeByStatus(status)

    fun search(query: String): Flow<List<BookEntity>> {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return bookDao.observeAll()
        val conditions = tokens.joinToString(" AND ") { token ->
            "(title_search LIKE '%' || '$token' || '%' " +
                "OR author_search LIKE '%' || '$token' || '%' " +
                "OR REPLACE(IFNULL(isbn13, ''), '-', '') LIKE '%' || '$token' || '%')"
        }
        val sql = "SELECT * FROM books WHERE $conditions ORDER BY updated_at DESC"
        return bookDao.rawSearch(SimpleSQLiteQuery(sql))
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

    suspend fun upsertByIsbn(book: BookEntity): Long {
        val existing = book.isbn13?.let { bookDao.findByIsbn(it) }
        return if (existing != null) {
            bookDao.update(withSearchKeys(book.copy(id = existing.id, updatedAt = System.currentTimeMillis())))
            existing.id
        } else {
            insert(book.copy(source = BookSource.TELLICO))
        }
    }

    suspend fun upsertFromImport(book: BookEntity): Long {
        val byIsbn = book.isbn13?.let { bookDao.findByIsbn(it) }
        if (byIsbn != null) {
            bookDao.update(withSearchKeys(book.copy(id = byIsbn.id, updatedAt = System.currentTimeMillis())))
            return byIsbn.id
        }
        val byName = bookDao.findByTitleAndAuthor(book.title, book.author)
        if (byName != null) {
            bookDao.update(withSearchKeys(book.copy(id = byName.id, updatedAt = System.currentTimeMillis())))
            return byName.id
        }
        return insert(book.copy(source = BookSource.TELLICO))
    }

    suspend fun update(book: BookEntity): Int =
        bookDao.update(withSearchKeys(book.copy(updatedAt = System.currentTimeMillis())))

    private fun withSearchKeys(book: BookEntity): BookEntity =
        book.copy(
            titleSearch = book.title.lowercase() + " " + (book.subtitle?.lowercase().orEmpty()),
            authorSearch = book.author.lowercase(),
        )

    suspend fun delete(book: BookEntity): Int {
        coverStorage.deleteForBook(book.id)
        return bookDao.delete(book)
    }

    suspend fun deleteById(id: Long): Int {
        coverStorage.deleteForBook(id)
        return bookDao.deleteById(id)
    }

    suspend fun count(): Int = bookDao.count()
}
