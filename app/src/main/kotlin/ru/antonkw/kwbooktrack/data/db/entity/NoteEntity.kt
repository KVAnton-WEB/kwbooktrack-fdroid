package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id"]),
        Index(value = ["created_at"]),
        Index(value = ["kind"]),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
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
