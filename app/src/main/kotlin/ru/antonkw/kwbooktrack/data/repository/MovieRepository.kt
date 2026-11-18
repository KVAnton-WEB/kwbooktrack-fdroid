package ru.antonkw.kwbooktrack.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.antonkw.kwbooktrack.data.db.dao.MovieDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фильмы — по образцу [BookRepository].
 *
 * Поиск идёт по двум предвычисленным колонкам: `title_search` (название и
 * оригинальное название) и `people_search` (режиссёры и актёры). SQLite не
 * складывает кириллицу регистронезависимо, поэтому обе колонки хранятся уже
 * в нижнем регистре.
 */
@Singleton
class MovieRepository @Inject constructor(
    private val movieDao: MovieDao,
    private val coverStorage: CoverStorage,
    private val photoRepository: PhotoRepository,
) {
    fun observeAll(): Flow<List<MovieEntity>> = movieDao.observeAll()

    /**
     * Все фильмы разом — для снимка библиотеки.
     *
     * Suspend, а не Flow: Flow-запрос Room выполняет вне транзакции, и снимок
     * собрался бы из разных состояний БД.
     */
    suspend fun getAll(): List<MovieEntity> = movieDao.getAll()

    fun search(query: String): Flow<List<MovieEntity>> {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return movieDao.observeAll()
        val conditions = tokens.joinToString(" AND ") {
            // Every LIKE target is already lowercased on write (withSearchKeys),
            // and the token above is lowercased too, so both sides of the
            // comparison are in the same case. Don't wrap these in LOWER():
            // SQLite folds case for ASCII only, so over Cyrillic it changes
            // nothing — it would read like it works while silently matching
            // nothing.
            "(title_search LIKE ? OR people_search LIKE ?)"
        }
        val sql = "SELECT * FROM movies WHERE $conditions ORDER BY updated_at DESC"
        // bindArgs removes the only way a token-derived literal could end up in
        // the SQL string itself.
        val args = tokens.flatMap { token -> List(2) { "%$token%" } }.toTypedArray()
        return movieDao.rawSearch(SimpleSQLiteQuery(sql, args))
    }

    suspend fun findById(id: Long): MovieEntity? = movieDao.findById(id)

    /** Фильм по id потоком — для экрана карточки. */
    fun observeById(id: Long): Flow<MovieEntity?> = movieDao.observeById(id)

    suspend fun insert(movie: MovieEntity): Long {
        val now = System.currentTimeMillis()
        val withTimestamps = movie.copy(
            createdAt = if (movie.createdAt == 0L) now else movie.createdAt,
            updatedAt = now,
        )
        return movieDao.insert(withSearchKeys(withTimestamps))
    }

    suspend fun update(movie: MovieEntity): Int =
        movieDao.update(withSearchKeys(movie.copy(updatedAt = System.currentTimeMillis())))

    /**
     * Обновление после импорта: метку «изменено» не трогаем.
     *
     * Обычный [update] ставит `updatedAt = сейчас` — для импорта это неверно:
     * фильм изменили не сейчас, а когда его редактировали в источнике
     * (`mdate` в Tellico, `updated_at` в zip-бэкапе).
     */
    suspend fun updateFromImport(movie: MovieEntity): Int = movieDao.update(withSearchKeys(movie))

    /**
     * Вставка или обновление фильма из импорта.
     *
     * @param snapshotId id строки в архиве (свой zip-бэкап): по нему запись
     *   обновляется точно, а не по совпадению названия и года.
     */
    suspend fun upsertFromImport(movie: MovieEntity, snapshotId: Long = 0L): Long =
        movieDao.upsertFromImport(withSearchKeys(movie), snapshotId)

    suspend fun deleteById(id: Long): Int {
        val coverPath = movieDao.findById(id)?.coverPath
        val photoPaths = photoRepository.observeByMedia(id, MediaType.MOVIES).first().map { it.path }
        val rows = movieDao.deleteWithContent(id)
        if (rows > 0) {
            photoRepository.deleteFiles(photoPaths)
            coverStorage.delete(coverPath)
        }
        return rows
    }

    /** Счётчик фильмов потоком — без загрузки строк. */
    fun observeCount(): Flow<Int> = movieDao.observeCount()

    private fun withSearchKeys(movie: MovieEntity): MovieEntity = movie.copy(
        titleSearch = listOfNotNull(movie.title, movie.originalTitle)
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase() },
        // Lowercased here rather than in SQL: Kotlin's lowercase() folds
        // Cyrillic, SQLite's lower() does not on AOSP (no ICU build), so a
        // SQL-side LOWER() would silently leave Cyrillic untouched.
        peopleSearch = listOfNotNull(movie.director, movie.cast)
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase() },
    )
}
