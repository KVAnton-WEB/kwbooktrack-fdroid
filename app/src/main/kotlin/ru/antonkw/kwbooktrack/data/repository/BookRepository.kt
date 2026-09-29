package ru.antonkw.kwbooktrack.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
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

    fun observeByStatus(status: BookStatus): Flow<List<BookEntity>> = bookDao.observeByStatus(status)

    fun search(query: String): Flow<List<BookEntity>> {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return bookDao.observeAll()
        val conditions = tokens.joinToString(" AND ") {
            "(title_search LIKE ? OR author_search LIKE ? OR REPLACE(IFNULL(isbn13, ''), '-', '') LIKE ?)"
        }
        val sql = "SELECT * FROM books WHERE $conditions ORDER BY updated_at DESC"
        // Bind the same argument 3× per token — one per LIKE position.
        // bindArgs removes the only way a token-derived literal could end up in the
        // SQL string itself.
        val args = tokens.flatMap { token -> List(3) { "%$token%" } }.toTypedArray()
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

    private fun withSearchKeys(book: BookEntity): BookEntity =
        book.copy(
            titleSearch = book.title.lowercase() + " " + (book.subtitle?.lowercase().orEmpty()),
            authorSearch = book.author.lowercase(),
        )

    suspend fun delete(book: BookEntity): Int {
        // Delete the row first; if DAO throws, the cover file remains, which is
        // harmless (orphan on disk) compared to a missing cover file with a row
        // still pointing at it.
        val rows = bookDao.delete(book)
        if (rows > 0) {
            // Pull orphan photo paths before the CASCADE wiped them; without
            // this the actual files in filesDir/photos/ leak forever.
            photoRepository.deleteOrphanFilesForBook(book.id)
            coverStorage.deleteForBook(book.id)
        }
        return rows
    }

    suspend fun deleteById(id: Long): Int {
        val rows = bookDao.deleteById(id)
        if (rows > 0) {
            photoRepository.deleteOrphanFilesForBook(id)
            coverStorage.deleteForBook(id)
        }
        return rows
    }

    suspend fun count(): Int = bookDao.count()
}
