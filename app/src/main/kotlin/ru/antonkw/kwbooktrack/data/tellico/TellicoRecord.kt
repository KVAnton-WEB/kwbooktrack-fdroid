package ru.antonkw.kwbooktrack.data.tellico

import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

/**
 * Запись коллекции Tellico.
 *
 * @param collectionType тип коллекции: 2 — книги, 3 — фильмы. По нему
 *   импортёр выбирает маппер: у книг и фильмов разные поля.
 */
data class TellicoRecord(
    val fields: Map<String, String> = emptyMap(),
    val extra: Map<String, ExtraField> = emptyMap(),
    val collectionType: Int = TellicoFields.COLLECTION_BOOKS,
)
