package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
)

@Entity(
    tableName = "book_tags",
    primaryKeys = ["book_id", "tag_id"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
        androidx.room.ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id"]),
        Index(value = ["tag_id"]),
    ],
)
data class BookTagCrossRef(
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)

/**
 * Имя тега книги — результат одного запроса по всем связям.
 *
 * Отдельный запрос на книгу стоил бы запроса на каждую книгу библиотеки.
 */
data class BookTagName(
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    @ColumnInfo(name = "name")
    val name: String,
)
