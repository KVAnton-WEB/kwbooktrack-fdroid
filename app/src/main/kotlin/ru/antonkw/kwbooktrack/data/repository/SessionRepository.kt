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

    suspend fun insert(session: ReadingSessionEntity): Long = sessionDao.insert(session)
}
