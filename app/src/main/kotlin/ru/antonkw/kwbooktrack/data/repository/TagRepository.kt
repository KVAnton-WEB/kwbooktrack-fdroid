package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
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

    /** Привязать тег к владельцу — книге или фильму. */
    suspend fun attachToMedia(mediaId: Long, mediaType: MediaType, tagId: Long) {
        tagDao.insertCrossRef(MediaTagCrossRef(mediaId = mediaId, mediaType = mediaType, tagId = tagId))
    }

    /** Теги владельца — книга или фильм. */
    suspend fun tagsOfMedia(mediaId: Long, mediaType: MediaType): List<String> =
        tagDao.tagsOfMedia(mediaId, mediaType)

    /** Теги владельца — потоком, чтобы список обновлялся при их изменении. */
    fun observeTagsOfMedia(mediaId: Long, mediaType: MediaType): Flow<List<String>> =
        tagDao.observeTagsOfMedia(mediaId, mediaType)
}
