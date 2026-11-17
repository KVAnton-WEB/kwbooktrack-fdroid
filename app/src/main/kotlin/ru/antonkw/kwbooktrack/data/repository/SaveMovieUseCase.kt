package ru.antonkw.kwbooktrack.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import javax.inject.Inject

/**
 * Сохраняет фильм и его дополнительные даты просмотра **одной транзакцией**.
 *
 * Как у книги: без транзакции между записью фильма и записью дат могла
 * прерваться программа, и фильм остался бы без дат пересмотра.
 *
 * @return id записанной строки или -1, если запись не прошла.
 */
class SaveMovieUseCase @Inject constructor(
    private val database: KwBooktrackDatabase,
    private val movieRepository: MovieRepository,
    private val mediaDateRepository: MediaDateRepository,
) {

    suspend operator fun invoke(movie: MovieEntity, dates: List<Long>): Long =
        database.withTransaction {
            try {
                val id = if (movie.id > 0L) {
                    if (movieRepository.update(movie) == 0) -1L else movie.id
                } else {
                    movieRepository.insert(movie)
                }
                if (id <= 0L) return@withTransaction -1L
                mediaDateRepository.replaceFor(id, MediaType.MOVIES, dates)
                id
            } catch (e: SQLiteConstraintException) {
                -1L
            }
        }
}
