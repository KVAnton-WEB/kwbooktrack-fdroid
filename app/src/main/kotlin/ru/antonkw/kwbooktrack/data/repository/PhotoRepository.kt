package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao,
    private val photoStorage: PhotoStorage,
) {
    /** Фото владельца — книга или фильм. */
    fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<PhotoEntity>> =
        photoDao.observeByMedia(mediaId, mediaType)

    fun observeByNote(noteId: Long): Flow<List<PhotoEntity>> = photoDao.observeByNote(noteId)

    suspend fun insert(photo: PhotoEntity): Long = photoDao.insert(photo)

    suspend fun delete(photo: PhotoEntity): Int = photoDao.delete(photo)

    /** Удалить файлы фото по известным путям. */
    suspend fun deleteFiles(paths: Collection<String>) {
        photoStorage.deletePaths(paths)
    }
}
