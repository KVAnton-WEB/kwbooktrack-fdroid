package ru.antonkw.kwbooktrack.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<BookEntity>>

    /**
     * Все книги разом — для снимка библиотеки.
     *
     * Suspend, а не Flow: Flow-запрос Room выполняет вне транзакции, и снимок
     * собрался бы из разных состояний БД.
     */
    @Query("SELECT * FROM books ORDER BY updated_at DESC")
    suspend fun getAll(): List<BookEntity>

    /**
     * Paging-friendly version of [observeAll] for libraries that exceed the
     * memory budget for a one-shot load (≥10k books). Current screens still
     * use [observeAll]; this is here so the call site is ready when the
     * BookListScreen is migrated.
     */
    @Query("SELECT * FROM books ORDER BY updated_at DESC")
    fun pagingSource(): PagingSource<Int, BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE isbn13 = :isbn13 LIMIT 1")
    suspend fun findByIsbn(isbn13: String): BookEntity?

    @Query(
        """
        SELECT * FROM books WHERE title = :title AND author = :author 
        ORDER BY updated_at DESC LIMIT 1
        """,
    )
    suspend fun findByTitleAndAuthor(title: String, author: String): BookEntity?

    @Query("SELECT * FROM books WHERE status = :status ORDER BY updated_at DESC")
    fun observeByStatus(status: BookStatus): Flow<List<BookEntity>>

    @RawQuery(observedEntities = [BookEntity::class])
    fun rawSearch(query: SupportSQLiteQuery): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity): Int

    @Delete
    suspend fun delete(book: BookEntity): Int

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    /**
     * Upsert by ISBN, merging non-null fields so manual user data
     * (purchaseDate, custom genre, rating) is not wiped by an import.
     * Wrapped in @Transaction to prevent two concurrent calls both
     * passing findByIsbn with null and double-inserting.
     */
    @Transaction
    suspend fun upsertByIsbn(book: BookEntity): Long {
        val existing = book.isbn13?.let { findByIsbn(it) }
        return if (existing != null) {
            val merged = book.mergeWith(existing)
            update(merged)
            existing.id
        } else {
            insert(book)
        }
    }

    /**
     * Upsert by ISBN first, then by title+author. Same merge rules and
     * @Transaction guarantee as [upsertByIsbn].
     */
    @Transaction
    suspend fun upsertFromImport(book: BookEntity): Long {
        val byIsbn = book.isbn13?.let { findByIsbn(it) }
        val existing = byIsbn ?: findByTitleAndAuthor(book.title, book.author)
        return if (existing != null) {
            val merged = book.mergeWith(existing)
            update(merged)
            existing.id
        } else {
            insert(book)
        }
    }
}

/**
 * Merge for imports: keep the existing row's non-null fields when the
 * incoming value is null/default. Source is preserved so re-importing via
 * Tellico does not overwrite Bookmory-sourced rows (and vice versa).
 *
 * Booleans are not merged — Tellico's `Подарок` cell is "да"/"нет"/blank,
 * and "да"→true is the only state we care about. A re-import with a blank
 * cell writes the import-side default (false), which is the desired
 * "lost in source → drop the flag" semantics.
 */
private fun BookEntity.mergeWith(existing: BookEntity): BookEntity = copy(
    id = existing.id,
    createdAt = existing.createdAt,
    updatedAt = System.currentTimeMillis(),
    isbn13 = isbn13 ?: existing.isbn13,
    subtitle = subtitle ?: existing.subtitle,
    pageCount = pageCount ?: existing.pageCount,
    language = language.takeIf { it.isNotBlank() && it != "ru" } ?: existing.language,
    genre = genre ?: existing.genre,
    publisher = publisher ?: existing.publisher,
    year = year ?: existing.year,
    coverPath = coverPath ?: existing.coverPath,
    rating = rating ?: existing.rating,
    status = if (status == BookStatus.WISHLIST && existing.status != BookStatus.WISHLIST) existing.status else status,
    startedAt = startedAt ?: existing.startedAt,
    finishedAt = finishedAt ?: existing.finishedAt,
    source = existing.source,
    purchaseDate = purchaseDate ?: existing.purchaseDate,
    purchasePrice = purchasePrice ?: existing.purchasePrice,
    // v5 — every nullable field falls back to the existing value so an
    // empty CSV cell (Bookmory importer, malformed row, omitted column)
    // doesn't overwrite a field the user set by hand.
    binding = binding ?: existing.binding,
    purchasePlace = purchasePlace ?: existing.purchasePlace,
    series = series ?: existing.series,
    seriesNumber = seriesNumber ?: existing.seriesNumber,
    condition = condition ?: existing.condition,
    translator = translator ?: existing.translator,
    printYear = printYear ?: existing.printYear,
    printPages = printPages ?: existing.printPages,
    originalLanguage = originalLanguage ?: existing.originalLanguage,
    originalTitle = originalTitle ?: existing.originalTitle,
    annotation = annotation ?: existing.annotation,
    isGift = isGift.takeIf { it } ?: existing.isGift,
    isLent = isLent.takeIf { it } ?: existing.isLent,
    isSold = isSold.takeIf { it } ?: existing.isSold,
    // Чужие поля Tellico: импорт побеждает по ключу, а ключи, которых в
    // импорте нет, остаются (пустой импорт не стирает данные).
    extraFields = existing.extraFields + extraFields,
)
