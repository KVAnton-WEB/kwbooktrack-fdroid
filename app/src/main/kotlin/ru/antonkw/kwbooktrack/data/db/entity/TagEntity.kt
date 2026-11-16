package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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

/**
 * Связь тега с владельцем — книгой **или** фильмом.
 *
 * Владелец входит в первичный ключ: id книг и id фильмов считаются
 * независимо и могут совпадать, поэтому одного `media_id` мало.
 */
@Entity(
    tableName = "media_tags",
    primaryKeys = ["media_id", "media_type", "tag_id"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["media_id"]),
        Index(value = ["tag_id"]),
    ],
)
data class MediaTagCrossRef(
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)

/**
 * Имя тега владельца — результат одного запроса по всем связям.
 *
 * Отдельный запрос на владельца стоил бы запроса на каждую книгу библиотеки.
 */
data class MediaTagName(
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "name")
    val name: String,
)
