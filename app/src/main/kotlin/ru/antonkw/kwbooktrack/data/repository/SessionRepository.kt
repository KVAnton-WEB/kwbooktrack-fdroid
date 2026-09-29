package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: ReadingSessionDao,
) {
    fun observeAll(): Flow<List<ReadingSessionEntity>> = sessionDao.observeAll()

    fun observeByBook(bookId: Long): Flow<List<ReadingSessionEntity>> =
        sessionDao.observeByBook(bookId)

    suspend fun latestForBook(bookId: Long): ReadingSessionEntity? =
        sessionDao.latestForBook(bookId)

    suspend fun maxEndPage(bookId: Long): Int? = sessionDao.maxEndPage(bookId)

    suspend fun totalDurationSec(bookId: Long): Long? = sessionDao.totalDurationSec(bookId)

    suspend fun insert(session: ReadingSessionEntity): Long = sessionDao.insert(session)

    suspend fun update(session: ReadingSessionEntity): Int = sessionDao.update(session)

    suspend fun delete(session: ReadingSessionEntity): Int = sessionDao.delete(session)

    suspend fun deleteForBook(bookId: Long): Int = sessionDao.deleteForBook(bookId)
}
