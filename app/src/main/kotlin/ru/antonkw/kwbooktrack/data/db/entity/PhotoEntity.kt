package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["book_id"]),
        Index(value = ["note_id"]),
    ],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "note_id")
    val noteId: Long? = null,
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
