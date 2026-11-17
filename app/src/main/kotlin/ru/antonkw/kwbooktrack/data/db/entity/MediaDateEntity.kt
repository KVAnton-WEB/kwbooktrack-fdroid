package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Дополнительная дата просмотра фильма или прочтения книги.
 *
 * Основная дата лежит в самой записи (`movies.watched_at`,
 * `books.finished_at`) — она же самая ранняя. Здесь остальные: пересмотры и
 * перечитывания.
 *
 * Внешнего ключа на владельца нет: владельцем может быть и книга, и фильм,
 * а общей таблицы записей у них нет.
 */
@Entity(
    tableName = "media_dates",
    indices = [
        Index(value = ["media_id"]),
        Index(value = ["media_type"]),
    ],
)
data class MediaDateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "date")
    val date: Long,
)
