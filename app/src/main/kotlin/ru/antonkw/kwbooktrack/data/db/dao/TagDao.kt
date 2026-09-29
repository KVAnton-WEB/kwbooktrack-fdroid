package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.antonkw.kwbooktrack.data.db.entity.BookTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: BookTagCrossRef): Long

    @Query("DELETE FROM book_tags WHERE book_id = :bookId")
    suspend fun clearCrossRefsForBook(bookId: Long): Int

    @Query("DELETE FROM tags")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int
}
