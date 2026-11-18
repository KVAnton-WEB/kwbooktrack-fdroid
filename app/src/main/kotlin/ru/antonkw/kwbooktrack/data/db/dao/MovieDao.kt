package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
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

    /** Фильм по id потоком — для экрана карточки. */
    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<MovieEntity?>

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

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun count(): Int

    /** Счётчик фильмов потоком — экран «О приложении» считает, а не грузит. */
    @Query("SELECT COUNT(*) FROM movies")
    fun observeCount(): Flow<Int>

    /**
     * Upsert по id архива, затем по названию и году — ключ фильма в Tellico.
     *
     * @param snapshotId id строки в архиве (свой zip-бэкап): по нему запись
     *   обновляется точно.
     */
    @Transaction
    suspend fun upsertFromImport(movie: MovieEntity, snapshotId: Long = 0L): Long {
        // Точное совпадение по id архива: та же самая строка, её надо обновить.
        if (snapshotId > 0L) {
            val byId = findById(snapshotId)
            if (byId != null && byId.title == movie.title) {
                update(movie.mergeWith(byId))
                return byId.id
            }
        }
        // Слабый ключ — название и год: так совпадают и два разных фильма.
        // Заполненные поля существующей строки не трогаем.
        val existing = findByTitleAndYear(movie.title, movie.year)
        return if (existing != null) {
            update(movie.mergeIntoExisting(existing))
            existing.id
        } else {
            // Новая строка: id из архива сохраняем, если он свободен — тогда
            // следующий импорт найдёт этот фильм точно, а не по названию.
            insert(if (snapshotId > 0L && findById(snapshotId) == null) {
                movie.copy(id = snapshotId)
            } else {
                movie
            })
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
        deleteDates(movieId)
        return deleteById(movieId)
    }

    @Query("DELETE FROM media_tags WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deleteTagRefs(movieId: Long): Int

    @Query("DELETE FROM photos WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deletePhotos(movieId: Long): Int

    @Query("DELETE FROM notes WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deleteNotes(movieId: Long): Int

    /** Дополнительные даты просмотра — внешнего ключа на владельца нет. */
    @Query("DELETE FROM media_dates WHERE media_id = :movieId AND media_type = 'MOVIES'")
    suspend fun deleteDates(movieId: Long): Int
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
    // Метка «изменено» — из источника (`mdate` в Tellico, `updated_at` в
    // zip-бэкапе), а не «сейчас»: фильм изменили не сейчас, а когда его
    // редактировали в источнике.
    updatedAt = updatedAt.takeIf { it > 0L } ?: existing.updatedAt,
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
    // Флаг «скачан» — как флаги книги: отметка пользователя не теряется,
    // если в импорте её нет.
    isDownloaded = isDownloaded.takeIf { it } ?: existing.isDownloaded,
    source = existing.source,
    // Чужие поля Tellico: импорт побеждает по ключу, а ключи, которых в
    // импорте нет, остаются (пустой импорт не стирает данные).
    extraFields = existing.extraFields + extraFields,
)

/**
 * Слияние по слабому ключу (название и год): заполненные поля существующей
 * строки не трогаем.
 *
 * Название и год — не уникальный ключ: так совпадают и два разных фильма
 * (перезапись, ремейк с тем же годом). Раньше вторая запись затирала режиссёра
 * и описание первого.
 */
private fun MovieEntity.mergeIntoExisting(existing: MovieEntity): MovieEntity = existing.copy(
    // Метка «изменено» — из источника (`mdate` в Tellico, `updated_at` в
    // zip-бэкапе), а не «сейчас».
    updatedAt = updatedAt.takeIf { it > 0L } ?: existing.updatedAt,
    originalTitle = existing.originalTitle ?: originalTitle,
    year = existing.year ?: year,
    genre = existing.genre ?: genre,
    nationality = existing.nationality ?: nationality,
    director = existing.director ?: director,
    cast = existing.cast ?: cast,
    durationMin = existing.durationMin ?: durationMin,
    rating = existing.rating ?: rating,
    watchedAt = existing.watchedAt ?: watchedAt,
    coverPath = existing.coverPath ?: coverPath,
    plot = existing.plot ?: plot,
    comments = existing.comments ?: comments,
    url = existing.url ?: url,
    isDownloaded = existing.isDownloaded || isDownloaded,
    source = existing.source,
    extraFields = existing.extraFields + extraFields,
)
