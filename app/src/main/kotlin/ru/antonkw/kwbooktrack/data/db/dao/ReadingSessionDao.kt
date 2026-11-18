package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity): Long
}
