package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.MediaDateEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/** Дополнительные даты просмотра и прочтения. */
@Dao
interface MediaDateDao {

    @Query("SELECT * FROM media_dates WHERE media_id = :mediaId AND media_type = :mediaType ORDER BY date")
    suspend fun getFor(mediaId: Long, mediaType: MediaType): List<MediaDateEntity>

    @Query("SELECT * FROM media_dates ORDER BY media_id, media_type, date")
    suspend fun getAll(): List<MediaDateEntity>

    @Query("SELECT * FROM media_dates ORDER BY media_id, media_type, date")
    fun observeAll(): Flow<List<MediaDateEntity>>

    @Query("SELECT * FROM media_dates WHERE media_id = :mediaId AND media_type = :mediaType ORDER BY date")
    fun observeFor(mediaId: Long, mediaType: MediaType): Flow<List<MediaDateEntity>>

    @Insert
    suspend fun insert(date: MediaDateEntity): Long

    @Query("DELETE FROM media_dates WHERE media_id = :mediaId AND media_type = :mediaType")
    suspend fun deleteFor(mediaId: Long, mediaType: MediaType): Int

    /**
     * Заменить список дат владельца целиком — одной транзакцией.
     *
     * Иначе падение между удалением и вставкой оставило бы владельца вовсе
     * без дополнительных дат.
     */
    @Transaction
    suspend fun replaceFor(mediaId: Long, mediaType: MediaType, dates: List<Long>) {
        deleteFor(mediaId, mediaType)
        dates.forEach {
            insert(MediaDateEntity(mediaId = mediaId, mediaType = mediaType, date = it))
        }
    }

    @Query("SELECT COUNT(*) FROM media_dates")
    suspend fun count(): Int
}
