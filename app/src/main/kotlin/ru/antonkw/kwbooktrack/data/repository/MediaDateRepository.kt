package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.MediaDateDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaDateEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Дополнительные даты просмотра фильма и прочтения книги.
 *
 * Основная дата лежит в самой записи; здесь — остальные. Список заменяется
 * целиком: редактор приносит его таким, каким пользователь его оставил.
 */
@Singleton
class MediaDateRepository @Inject constructor(
    private val dao: MediaDateDao,
) {
    fun observeAll(): Flow<List<MediaDateEntity>> = dao.observeAll()

    fun observeFor(mediaId: Long, mediaType: MediaType): Flow<List<MediaDateEntity>> =
        dao.observeFor(mediaId, mediaType)

    /** Даты владельца — по возрастанию. */
    suspend fun getFor(mediaId: Long, mediaType: MediaType): List<Long> =
        dao.getFor(mediaId, mediaType).map { it.date }

    /** Заменить список дат владельца — одной транзакцией. */
    suspend fun replaceFor(mediaId: Long, mediaType: MediaType, dates: List<Long>) =
        dao.replaceFor(mediaId, mediaType, dates)
}
