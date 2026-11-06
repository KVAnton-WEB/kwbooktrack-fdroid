package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    /** Все сессии разом — для снимка библиотеки (внутри транзакции). */
    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE book_id = :bookId ORDER BY started_at DESC")
    fun observeByBook(bookId: Long): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE book_id = :bookId ORDER BY started_at DESC LIMIT 1")
    suspend fun latestForBook(bookId: Long): ReadingSessionEntity?

    @Query("SELECT MAX(end_page) FROM reading_sessions WHERE book_id = :bookId")
    suspend fun maxEndPage(bookId: Long): Int?

    @Query("SELECT SUM(duration_sec) FROM reading_sessions WHERE book_id = :bookId")
    suspend fun totalDurationSec(bookId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity): Long

    @Update
    suspend fun update(session: ReadingSessionEntity): Int

    @Delete
    suspend fun delete(session: ReadingSessionEntity): Int

    @Query("DELETE FROM reading_sessions WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun count(): Int
}
