package ru.antonkw.kwbooktrack.data.db.converter

import androidx.room.TypeConverter
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind

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
}
