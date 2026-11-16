package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MovieEntity>>

    /**
     * Все фильмы разом — для снимка библиотеки.
     *
     * Suspend, а не Flow: Flow-запрос Room выполняет вне транзакции, и снимок
     * собрался бы из разных состояний БД.
     */
    @Query("SELECT * FROM movies ORDER BY updated_at DESC")
    suspend fun getAll(): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MovieEntity?

    /**
     * Ключ слияния при импорте — название и год: у фильма нет ISBN.
     *
     * `year IS :year` — а не `=`, чтобы фильм без года находился по году null.
     */
    @Query(
        """
        SELECT * FROM movies WHERE title = :title AND year IS :year
        ORDER BY updated_at DESC LIMIT 1
        """,
    )
    suspend fun findByTitleAndYear(title: String, year: Int?): MovieEntity?

    @RawQuery(observedEntities = [MovieEntity::class])
    fun rawSearch(query: SupportSQLiteQuery): Flow<List<MovieEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(movie: MovieEntity): Long

    @Update
    suspend fun update(movie: MovieEntity): Int

    @Delete
    suspend fun delete(movie: MovieEntity): Int

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int

    /**
     * Upsert по названию и году — ключ фильма в Tellico.
     *
     * Те же правила слияния, что у книги: заполненные поля не затираются
     * пустыми из импорта, источник сохраняется, флаги не сливаются.
     */
    @Transaction
    suspend fun upsertFromImport(movie: MovieEntity): Long {
        val existing = findByTitleAndYear(movie.title, movie.year)
        return if (existing != null) {
            update(movie.mergeWith(existing))
            existing.id
        } else {
            insert(movie)
        }
    }

    /**
     * Удалить фильм вместе с его заметками, фото и связями тегов.
     *
     * Внешнего ключа на `movies` у заметок, фото и тегов нет: владельцем может
     * быть и книга, и фильм, а id у них считаются независимо и совпадают.
     * Поэтому каскада не будет — содержимое удаляем сами, одной транзакцией.
     */
    @Transaction
    suspend fun deleteWithContent(movieId: Long): Int {
        deleteTagRefs(movieId)
        deletePhotos(movieId)
        deleteNotes(movieId)
        return deleteById(movieId)
    }

    @Query("DELETE FROM media_tags WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deleteTagRefs(movieId: Long): Int

    @Query("DELETE FROM photos WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deletePhotos(movieId: Long): Int

    @Query("DELETE FROM notes WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deleteNotes(movieId: Long): Int
}

/**
 * Merge для импорта: у существующего фильма не затираются заполненные поля.
 *
 * Источник сохраняется — повторный импорт из Tellico не должен переписывать
 * фильм, введённый вручную, и наоборот.
 */
private fun MovieEntity.mergeWith(existing: MovieEntity): MovieEntity = copy(
    id = existing.id,
    createdAt = existing.createdAt,
    updatedAt = System.currentTimeMillis(),
    originalTitle = originalTitle ?: existing.originalTitle,
    year = year ?: existing.year,
    genre = genre ?: existing.genre,
    nationality = nationality ?: existing.nationality,
    director = director ?: existing.director,
    cast = cast ?: existing.cast,
    durationMin = durationMin ?: existing.durationMin,
    rating = rating ?: existing.rating,
    watchedAt = watchedAt ?: existing.watchedAt,
    coverPath = coverPath ?: existing.coverPath,
    plot = plot ?: existing.plot,
    comments = comments ?: existing.comments,
    url = url ?: existing.url,
    source = existing.source,
    // Чужие поля Tellico: импорт побеждает по ключу, а ключи, которых в
    // импорте нет, остаются (пустой импорт не стирает данные).
    extraFields = existing.extraFields + extraFields,
)
