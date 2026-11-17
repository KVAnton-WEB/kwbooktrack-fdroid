package ru.antonkw.kwbooktrack.data.media

import ru.antonkw.kwbooktrack.data.db.entity.MediaDateEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Все даты владельца: основная плюс дополнительные, по возрастанию.
 *
 * Основная дата — самая ранняя, поэтому в списке она первая. Отсюда и
 * «первая дата» для Tellico, и неизменность фильтра «Просмотрено».
 */
fun mediaDates(primary: Long?, extra: List<Long>): List<Long> =
    (listOfNotNull(primary) + extra).sorted()

/**
 * Дополнительные даты по владельцам: ключ — пара (id, тип).
 *
 * id книг и фильмов считаются независимо и могут совпадать, поэтому одного
 * id мало.
 */
fun extrasByOwner(extras: List<MediaDateEntity>): Map<Pair<Long, MediaType>, List<Long>> =
    extras.groupBy({ it.mediaId to it.mediaType }, { it.date })

/**
 * Дополнительные даты владельцев одного типа: id → даты.
 *
 * Одна и та же группировка нужна списку, календарю, статистике и снимку —
 * разные копии правила разъезжались бы.
 */
fun extrasOfType(extras: List<MediaDateEntity>, mediaType: MediaType): Map<Long, List<Long>> =
    extras.filter { it.mediaType == mediaType }.groupBy({ it.mediaId }, { it.date })
