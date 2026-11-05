package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeByBook(bookId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE note_id = :noteId ORDER BY created_at DESC")
    fun observeByNote(noteId: Long): Flow<List<PhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity): Long

    @Delete
    suspend fun delete(photo: PhotoEntity): Int

    @Query("DELETE FROM photos WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    /** All paths currently linked to a book, used to delete orphan files after FK cascade. */
    @Query("SELECT path FROM photos WHERE book_id = :bookId")
    suspend fun pathsForBook(bookId: Long): List<String>
}
