package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY created_at DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    /** Все заметки разом — для снимка библиотеки (внутри транзакции). */
    @Query("SELECT * FROM notes ORDER BY created_at DESC")
    suspend fun getAll(): List<NoteEntity>

    /** Заметки владельца — книга или фильм. */
    @Query("SELECT * FROM notes WHERE media_id = :mediaId AND media_type = :mediaType ORDER BY created_at DESC")
    fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity): Int

    @Delete
    suspend fun delete(note: NoteEntity): Int
}
