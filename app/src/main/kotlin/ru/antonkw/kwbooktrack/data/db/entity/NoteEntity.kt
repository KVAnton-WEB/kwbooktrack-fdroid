package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Заметка, цитата или рецензия — книге **или** фильму.
 *
 * Внешнего ключа на `books` нет: владельцем может быть фильм, а фильма в
 * `books` нет, и внешний ключ его бы не пустил. Вместо него — пара
 * (`media_id`, `media_type`).
 *
 * Страница есть только у книг: у фильма её нет, и колонка остаётся пустой.
 */
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["media_id"]),
        Index(value = ["created_at"]),
        Index(value = ["kind"]),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType = MediaType.BOOKS,
    @ColumnInfo(name = "page")
    val page: Int? = null,
    @ColumnInfo(name = "body_md")
    val bodyMd: String,
    @ColumnInfo(name = "kind")
    val kind: NoteKind = NoteKind.NOTE,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
