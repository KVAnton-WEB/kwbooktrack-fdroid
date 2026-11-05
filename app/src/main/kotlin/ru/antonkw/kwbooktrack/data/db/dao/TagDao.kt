package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ru.antonkw.kwbooktrack.data.db.entity.BookTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    /** Теги книги по алфавиту — так же, как их показывает список тегов. */
    @Query(
        "SELECT t.* FROM tags t JOIN book_tags bt ON bt.tag_id = t.id " +
            "WHERE bt.book_id = :bookId ORDER BY t.name",
    )
    suspend fun tagsForBook(bookId: Long): List<TagEntity>

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

    /**
     * Atomic find-or-create. Two concurrent callers both seeing `findByName`
     * return null and both calling `insert` would race; the second insert
     * is silently ignored (-1) and the second caller's caller would attach
     * a tag to a non-existent id. @Transaction wraps the read+insert so
     * SQLite's row lock serializes them; the loser sees the winner's row
     * via a re-read.
     */
    @Transaction
    suspend fun findOrCreate(name: String): Long {
        val existing = findByName(name)
        if (existing != null) return existing.id
        val newId = insert(TagEntity(name = name))
        if (newId != -1L) return newId
        // Lost the race — someone inserted between our insert and now. Read theirs.
        return findByName(name)?.id ?: -1L
    }
}
