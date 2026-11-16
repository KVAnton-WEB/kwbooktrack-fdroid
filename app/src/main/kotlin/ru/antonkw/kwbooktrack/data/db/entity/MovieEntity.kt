package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

/**
 * Фильм.
 *
 * Отдельная таблица, а не ещё набор колонок в `books`: у фильма нет ISBN,
 * числа страниц, издательства и серии, а есть продолжительность, дата
 * просмотра, режиссёр и актёры. Общее у книг и фильмов — только заметки,
 * цитаты, рецензии, теги и фото, и они лежат в общих таблицах.
 *
 * Статуса нет: «просмотрен / не просмотрен» определяется по [watchedAt].
 */
@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["title_search"]),
        Index(value = ["people_search"]),
        Index(value = ["watched_at"]),
    ],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "original_title")
    val originalTitle: String? = null,
    @ColumnInfo(name = "year")
    val year: Int? = null,
    /** Жанры через "; " — несколько значений в одной колонке. */
    @ColumnInfo(name = "genre")
    val genre: String? = null,
    /** Страны через "; ". */
    @ColumnInfo(name = "nationality")
    val nationality: String? = null,
    /** Режиссёры через "; ". */
    @ColumnInfo(name = "director")
    val director: String? = null,
    /** Актёры: строки через "\n", ячейки «актёр::роль» через "::". */
    @ColumnInfo(name = "cast")
    val cast: String? = null,
    /** Продолжительность в минутах — из `running-time` Tellico. */
    @ColumnInfo(name = "duration_min")
    val durationMin: Int? = null,
    @ColumnInfo(name = "rating")
    val rating: Int? = null,
    /** Дата просмотра. */
    @ColumnInfo(name = "watched_at")
    val watchedAt: Long? = null,
    @ColumnInfo(name = "cover_path")
    val coverPath: String? = null,
    @ColumnInfo(name = "plot")
    val plot: String? = null,
    @ColumnInfo(name = "comments")
    val comments: String? = null,
    /** Ссылка на Кинопоиск. */
    @ColumnInfo(name = "url")
    val url: String? = null,
    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "source")
    val source: MediaSource = MediaSource.MANUAL,
    /** Название и оригинальное название в нижнем регистре — для поиска. */
    @ColumnInfo(name = "title_search")
    val titleSearch: String = "",
    /** Режиссёры и актёры в нижнем регистре — для поиска. */
    @ColumnInfo(name = "people_search")
    val peopleSearch: String = "",
    @ColumnInfo(name = "extra_fields")
    val extraFields: Map<String, ExtraField> = emptyMap(),
)
