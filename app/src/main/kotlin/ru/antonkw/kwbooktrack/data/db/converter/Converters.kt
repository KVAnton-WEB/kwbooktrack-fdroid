package ru.antonkw.kwbooktrack.data.db.converter

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

class Converters {
    @TypeConverter
    fun fromBookStatus(value: BookStatus): String = value.name

    @TypeConverter
    fun toBookStatus(value: String): BookStatus = runCatching { BookStatus.valueOf(value) }.getOrDefault(BookStatus.WISHLIST)

    @TypeConverter
    fun fromBookSource(value: BookSource): String = value.name

    @TypeConverter
    fun toBookSource(value: String): BookSource = runCatching { BookSource.valueOf(value) }.getOrDefault(BookSource.MANUAL)

    @TypeConverter
    fun fromNoteKind(value: NoteKind): String = value.name

    @TypeConverter
    fun toNoteKind(value: String): NoteKind = runCatching { NoteKind.valueOf(value) }.getOrDefault(NoteKind.NOTE)

    /**
     * Чужие поля Tellico (пользовательские, которых нет в приложении) —
     * JSON в колонке `books.extra_fields`:
     * `{"<имя>":{"t":"<заголовок>","v":"<значение>"}}`.
     *
     * Чтение не бросает: битая колонка (ручная правка, старый экспорт) даёт
     * пустой набор полей, а не падение приложения.
     */
    @TypeConverter
    fun fromExtraFields(value: Map<String, ExtraField>): String =
        if (value.isEmpty()) "{}" else EXTRA_JSON.encodeToString(value)

    @TypeConverter
    fun toExtraFields(value: String): Map<String, ExtraField> =
        if (value.isBlank() || value == "{}") {
            emptyMap()
        } else {
            runCatching { EXTRA_JSON.decodeFromString<Map<String, ExtraField>>(value) }.getOrDefault(emptyMap())
        }

    private companion object {
        val EXTRA_JSON = Json { ignoreUnknownKeys = true }
    }
}
