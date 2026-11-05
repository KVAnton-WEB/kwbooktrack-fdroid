package ru.antonkw.kwbooktrack.data.tellico

import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

/**
 * Промежуточная модель записи Tellico.
 *
 * Ключ [fields] — **внутреннее имя поля Tellico** (`title`, `место-покупки`),
 * а не человеческий заголовок: именно это имя кладётся в `<field name=…>`
 * при экспорте, поэтому `.tc` заполняет существующие поля коллекции, а не
 * создаёт дубликаты.
 */
data class TellicoRecord(
    val fields: Map<String, String> = emptyMap(),
    val extra: Map<String, ExtraField> = emptyMap(),
)
