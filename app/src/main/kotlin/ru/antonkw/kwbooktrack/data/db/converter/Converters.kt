package ru.antonkw.kwbooktrack.data.db.converter

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

class Converters {
    @TypeConverter
    fun fromBookStatus(value: BookStatus): String = value.name

    @TypeConverter
    fun toBookStatus(value: String): BookStatus = runCatching { BookStatus.valueOf(value) }.getOrDefault(BookStatus.WISHLIST)

    @TypeConverter
    fun fromMediaSource(value: MediaSource): String = value.name

    @TypeConverter
    fun toMediaSource(value: String): MediaSource = runCatching { MediaSource.valueOf(value) }.getOrDefault(MediaSource.MANUAL)

    @TypeConverter
    fun fromNoteKind(value: NoteKind): String = value.name

    @TypeConverter
    fun toNoteKind(value: String): NoteKind = runCatching { NoteKind.valueOf(value) }.getOrDefault(NoteKind.NOTE)

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    /**
     * Тип носителя по умолчанию — книга: заметки, написанные до появления
     * фильмов, принадлежат книгам.
     */
    @TypeConverter
    fun toMediaType(value: String): MediaType = runCatching { MediaType.valueOf(value) }.getOrDefault(MediaType.BOOKS)

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
