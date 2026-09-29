package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<BookEntity>>

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
}
