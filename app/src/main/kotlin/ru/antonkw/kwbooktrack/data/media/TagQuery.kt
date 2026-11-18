package ru.antonkw.kwbooktrack.data.media

import ru.antonkw.kwbooktrack.data.db.entity.MediaTagName
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * id владельцев, подходящих под запрос по тегам.
 *
 * Каждое слово запроса должно найтись в наборе тегов владельца: «сага
 * перечитал» находит только владельца с обоими тегами. Регистр складывается
 * в Kotlin, а не в SQL: SQLite без ICU не складывает кириллицу, и `LIKE`
 * молча не находил бы «Сага» по «сага».
 *
 * Частичное совпадение — как у текстового поиска: «саг» находит «сага».
 */
fun tagMatchedOwners(
    tags: List<MediaTagName>,
    query: String,
    mediaType: MediaType,
): Set<Long> {
    val tokens = query.lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptySet()
    return tags.asSequence()
        .filter { it.mediaType == mediaType }
        .groupBy { it.mediaId }
        .filterValues { ownerTags ->
            val lowered = ownerTags.map { it.name.lowercase() }
            tokens.all { token -> lowered.any { it.contains(token) } }
        }
        .keys
}