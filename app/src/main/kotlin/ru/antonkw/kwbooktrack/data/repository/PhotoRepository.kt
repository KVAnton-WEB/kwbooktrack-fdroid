package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao,
) {
    fun observeByBook(bookId: Long): Flow<List<PhotoEntity>> = photoDao.observeByBook(bookId)

    fun observeByNote(noteId: Long): Flow<List<PhotoEntity>> = photoDao.observeByNote(noteId)

    suspend fun insert(photo: PhotoEntity): Long = photoDao.insert(photo)

    suspend fun delete(photo: PhotoEntity): Int = photoDao.delete(photo)

    suspend fun deleteForBook(bookId: Long): Int = photoDao.deleteForBook(bookId)
}
