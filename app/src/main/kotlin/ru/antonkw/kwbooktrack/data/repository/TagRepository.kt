package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.BookTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao,
) {
    fun observeAll(): Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun findOrCreate(name: String): Long {
        val normalized = name.trim().trimStart('#')
        if (normalized.isEmpty()) return -1
        return tagDao.findOrCreate(normalized)
    }

    suspend fun attachToBook(bookId: Long, tagId: Long) {
        tagDao.insertCrossRef(BookTagCrossRef(bookId = bookId, tagId = tagId))
    }

    suspend fun clearForBook(bookId: Long): Int = tagDao.clearCrossRefsForBook(bookId)
}
