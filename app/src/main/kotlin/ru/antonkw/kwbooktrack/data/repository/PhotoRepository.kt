package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao,
    private val photoStorage: PhotoStorage,
) {
    fun observeByBook(bookId: Long): Flow<List<PhotoEntity>> = photoDao.observeByBook(bookId)

    fun observeByNote(noteId: Long): Flow<List<PhotoEntity>> = photoDao.observeByNote(noteId)

    suspend fun insert(photo: PhotoEntity): Long = photoDao.insert(photo)

    suspend fun delete(photo: PhotoEntity): Int = photoDao.delete(photo)

    suspend fun deleteForBook(bookId: Long): Int = photoDao.deleteForBook(bookId)

    /**
     * Delete the actual files for a book whose DB rows are about to be wiped
     * by CASCADE. Reads paths first so we know exactly which files to unlink,
     * then calls into the storage layer. BookRepository.delete uses this to
     * avoid leaving orphan files in filesDir/photos/ forever.
     */
    suspend fun deleteOrphanFilesForBook(bookId: Long) {
        val paths = photoDao.pathsForBook(bookId)
        photoStorage.deletePaths(paths)
    }
}
