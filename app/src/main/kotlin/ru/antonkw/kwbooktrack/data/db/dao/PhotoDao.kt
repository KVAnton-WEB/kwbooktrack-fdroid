package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    /** Все фото разом — для снимка библиотеки (внутри транзакции). */
    @Query("SELECT * FROM photos ORDER BY created_at DESC")
    suspend fun getAll(): List<PhotoEntity>

    /** Фото владельца — книга или фильм. */
    @Query("SELECT * FROM photos WHERE media_id = :mediaId AND media_type = :mediaType ORDER BY created_at DESC")
    fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE note_id = :noteId ORDER BY created_at DESC")
    fun observeByNote(noteId: Long): Flow<List<PhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity): Long

    @Delete
    suspend fun delete(photo: PhotoEntity): Int

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    /** Пути файлов, привязанных к владельцу, — чтобы удалить их перед удалением строк. */
    @Query("SELECT path FROM photos WHERE media_id = :mediaId AND media_type = :mediaType")
    suspend fun pathsForMedia(mediaId: Long, mediaType: MediaType): List<String>
}
