package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
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
        Index(value = ["started_at"]),
    ],
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    @ColumnInfo(name = "start_page")
    val startPage: Int? = null,
    @ColumnInfo(name = "end_page")
    val endPage: Int? = null,
    @ColumnInfo(name = "duration_sec")
    val durationSec: Long? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
)
