package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Фото, привязанное к книге **или** фильму.
 *
 * Как и у заметки, внешнего ключа на `books` нет — владелец задаётся парой
 * (`media_id`, `media_type`). А вот привязка к заметке остаётся: заметка
 * удаляется, фото живёт, но теряет ссылку (`ON DELETE SET NULL`).
 */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["media_id"]),
        Index(value = ["note_id"]),
    ],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType = MediaType.BOOKS,
    @ColumnInfo(name = "note_id")
    val noteId: Long? = null,
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
