package ru.antonkw.kwbooktrack.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["isbn13"], unique = true),
        Index(value = ["status"]),
        Index(value = ["started_at"]),
        Index(value = ["finished_at"]),
        Index(value = ["purchase_date"]),
        Index(value = ["title_search"]),
        Index(value = ["author_search"]),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "subtitle")
    val subtitle: String? = null,
    @ColumnInfo(name = "author")
    val author: String,
    @ColumnInfo(name = "isbn13")
    val isbn13: String? = null,
    @ColumnInfo(name = "page_count")
    val pageCount: Int? = null,
    @ColumnInfo(name = "language")
    val language: String = "ru",
    @ColumnInfo(name = "genre")
    val genre: String? = null,
    @ColumnInfo(name = "publisher")
    val publisher: String? = null,
    @ColumnInfo(name = "year")
    val year: Int? = null,
    @ColumnInfo(name = "cover_path")
    val coverPath: String? = null,
    @ColumnInfo(name = "rating")
    val rating: Int? = null,
    @ColumnInfo(name = "status")
    val status: BookStatus = BookStatus.WISHLIST,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "source")
    val source: BookSource = BookSource.MANUAL,
    @ColumnInfo(name = "purchase_date")
    val purchaseDate: Long? = null,
    @ColumnInfo(name = "purchase_price")
    val purchasePrice: Double? = null,
    @ColumnInfo(name = "title_search", defaultValue = "")
    val titleSearch: String = "",
    @ColumnInfo(name = "author_search", defaultValue = "")
    val authorSearch: String = "",
)
