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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind

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

    @Query("SELECT * FROM notes WHERE kind = :kind ORDER BY created_at DESC")
    fun observeByKind(kind: NoteKind): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findByIdRange(id: Long): NoteEntity?

    /**
     * Random note — pick an id uniformly in [1, count], retry a few times on
     * miss (gaps after deletes), fall back to ORDER BY RANDOM() as a safety
     * net. Avoids the O(n) scan + sort of ORDER BY RANDOM() on every call.
     */
    suspend fun randomOne(): NoteEntity? {
        val total = count()
        if (total == 0) return null
        repeat(8) {
            val candidate = (1..total).random()
            findByIdRange(candidate.toLong())?.let { return it }
        }
        return fallbackRandomOne()
    }

    @Query("SELECT * FROM notes ORDER BY RANDOM() LIMIT 1")
    suspend fun fallbackRandomOne(): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteEntity?

    @RawQuery(observedEntities = [NoteEntity::class])
    fun rawSearch(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity): Int

    @Delete
    suspend fun delete(note: NoteEntity): Int

    @Query("DELETE FROM notes WHERE media_id = :mediaId AND media_type = :mediaType")
    suspend fun deleteForMedia(mediaId: Long, mediaType: MediaType): Int

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM notes WHERE kind = :kind")
    suspend fun countByKind(kind: NoteKind): Int
}
