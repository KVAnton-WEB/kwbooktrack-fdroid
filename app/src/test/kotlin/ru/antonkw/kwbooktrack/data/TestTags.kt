package ru.antonkw.kwbooktrack.data

import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagName
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Теги книги по одному запросу.
 *
 * В DAO нет запроса на книгу: сбор снимка берёт все теги разом (запрос на книгу
 * стоил бы запроса на каждую книгу библиотеки).
 */
suspend fun TagDao.tagsOf(bookId: Long): List<MediaTagName> =
    tagsForAllMedia().filter { it.mediaId == bookId && it.mediaType == MediaType.BOOKS }
