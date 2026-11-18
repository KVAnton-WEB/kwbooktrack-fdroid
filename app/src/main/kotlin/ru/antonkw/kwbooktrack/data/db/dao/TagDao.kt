package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagName
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    /**
     * Теги всех владельцев по одному запросу — по алфавиту, как их показывает
     * список тегов.
     *
     * Запрос на владельца стоил бы запроса на каждую книгу библиотеки.
     */
    @Query(
        "SELECT mt.media_id AS media_id, mt.media_type AS media_type, t.name AS name FROM tags t " +
            "JOIN media_tags mt ON mt.tag_id = t.id ORDER BY t.name",
    )
    suspend fun tagsForAllMedia(): List<MediaTagName>

    /** Теги одного владельца — книга или фильм. */
    @Query(
        "SELECT t.name AS name FROM tags t JOIN media_tags mt ON mt.tag_id = t.id " +
            "WHERE mt.media_id = :mediaId AND mt.media_type = :mediaType ORDER BY t.name",
    )
    suspend fun tagsOfMedia(mediaId: Long, mediaType: MediaType): List<String>

    /** Все существующие имена тегов — для подсказок при вводе. */
    @Query("SELECT name FROM tags ORDER BY name")
    fun observeAllNames(): kotlinx.coroutines.flow.Flow<List<String>>

    /**
     * Теги всех владельцев потоком — для поиска по тегам.
     *
     * Room инвалидирует запрос и по `tags`, и по `media_tags`: добавленный
     * тег сразу меняет выдачу.
     */
    @Query(
        "SELECT mt.media_id AS media_id, mt.media_type AS media_type, t.name AS name FROM tags t " +
            "JOIN media_tags mt ON mt.tag_id = t.id ORDER BY t.name",
    )
    fun observeTagsForAllMedia(): kotlinx.coroutines.flow.Flow<List<MediaTagName>>

    /** Отвязать тег по имени: удалять саму строку тега не нужно. */
    @Query(
        "DELETE FROM media_tags WHERE media_id = :mediaId AND media_type = :mediaType " +
            "AND tag_id = (SELECT id FROM tags WHERE name = :name)",
    )
    suspend fun deleteCrossRefByName(mediaId: Long, mediaType: MediaType, name: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: MediaTagCrossRef): Long

    /**
     * Теги владельца — потоком: список должен обновляться, когда теги
     * меняются, а не когда меняется сам владелец.
     */
    @Query(
        "SELECT t.name AS name FROM tags t " +
            "JOIN media_tags mt ON mt.tag_id = t.id " +
            "WHERE mt.media_id = :mediaId AND mt.media_type = :mediaType ORDER BY t.name",
    )
    fun observeTagsOfMedia(mediaId: Long, mediaType: MediaType): kotlinx.coroutines.flow.Flow<List<String>>

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
