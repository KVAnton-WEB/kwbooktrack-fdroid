package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagName
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao,
) {
    suspend fun findOrCreate(name: String): Long {
        val normalized = name.trim().trimStart('#')
        if (normalized.isEmpty()) return -1
        return tagDao.findOrCreate(normalized)
    }

    /**
     * Привязать тег к владельцу — книге или фильму.
     *
     * @return true, если привязка появилась; false, если она уже была —
     *   повторный импорт не должен показывать «добавлено», чего не добавили.
     */
    suspend fun attachToMedia(mediaId: Long, mediaType: MediaType, tagId: Long): Boolean =
        tagDao.insertCrossRef(MediaTagCrossRef(mediaId = mediaId, mediaType = mediaType, tagId = tagId)) != -1L

    /** Теги владельца — потоком, чтобы список обновлялся при их изменении. */
    fun observeTagsOfMedia(mediaId: Long, mediaType: MediaType): Flow<List<String>> =
        tagDao.observeTagsOfMedia(mediaId, mediaType)

    /** Все известные имена тегов — подсказки при вводе. */
    fun observeAllNames(): Flow<List<String>> = tagDao.observeAllNames()

    /** Теги всех владельцев — поиск по тегу в списке библиотеки. */
    fun observeAll(): Flow<List<MediaTagName>> = tagDao.observeTagsForAllMedia()

    /** Привязать тег по имени: тег создаётся, если такого ещё нет. */
    suspend fun attach(mediaId: Long, mediaType: MediaType, name: String): Boolean {
        val tagId = findOrCreate(name)
        if (tagId <= 0L) return false
        return attachToMedia(mediaId, mediaType, tagId)
    }

    /** Отвязать тег по имени. Сама строка тега остаётся — её могут использовать другие. */
    suspend fun detach(mediaId: Long, mediaType: MediaType, name: String): Int =
        tagDao.deleteCrossRefByName(mediaId, mediaType, name.trim().trimStart('#'))
}
