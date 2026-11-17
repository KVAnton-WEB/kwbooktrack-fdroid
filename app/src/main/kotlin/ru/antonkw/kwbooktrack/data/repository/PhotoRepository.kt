package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import java.io.InputStream
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

    /**
     * Удалить фото: строку БД и файл на диске.
     *
     * Файл принадлежит строке, значит и убирать должен он. Раньше файл
     * оставался: `deleteForNote` знает только имена `note_{id}_*`, а
     * импортированные фото лежат под именами `{md5}_{тип}_{id}` — на диске
     * копился мусор, которого нигде не видно.
     */
    suspend fun delete(photo: PhotoEntity): Int {
        // Сначала строка: если удаление файла не выйдет, останется файл
        // (это безвредно), а наоборот — строка, указывающая на
        // отсутствующий файл.
        val deleted = photoDao.delete(photo)
        photoStorage.deletePaths(listOf(photo.path))
        return deleted
    }

    /**
     * Сохранить файл фото и вернуть его путь.
     *
     * Имя с UUID (внутри PhotoStorage): два фото, прикреплённые в одну
     * миллисекунду, не должны получать одно имя.
     */
    suspend fun saveForNote(noteId: Long, source: InputStream, extension: String): String =
        photoStorage.saveForNote(noteId, source, extension)

    /** Удалить файлы фото по известным путям. */
    suspend fun deleteFiles(paths: Collection<String>) {
        photoStorage.deletePaths(paths)
    }
}
