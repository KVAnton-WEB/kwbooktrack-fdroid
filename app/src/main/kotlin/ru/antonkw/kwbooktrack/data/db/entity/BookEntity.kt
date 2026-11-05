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
    @ColumnInfo(name = "title_search")
    val titleSearch: String = "",
    @ColumnInfo(name = "author_search")
    val authorSearch: String = "",
    // --- v6: series search key ---
    // Pre-lowercased "series series_number". Needed because SQLite's own
    // lower()/LIKE fold case for ASCII only — AOSP's build has no ICU — so
    // a Cyrillic query can't match a stored series without it. Same trick as
    // title_search/author_search; see BookRepository.withSearchKeys.
    @ColumnInfo(name = "series_search")
    val seriesSearch: String = "",
    // --- v5: extended Tellico fields ---
    @ColumnInfo(name = "binding")
    val binding: String? = null,
    @ColumnInfo(name = "purchase_place")
    val purchasePlace: String? = null,
    @ColumnInfo(name = "series")
    val series: String? = null,
    @ColumnInfo(name = "series_number")
    val seriesNumber: String? = null,
    @ColumnInfo(name = "condition")
    val condition: String? = null,
    @ColumnInfo(name = "translator")
    val translator: String? = null,
    @ColumnInfo(name = "print_year")
    val printYear: Int? = null,
    // Усл. печ. л. — дробное число («32,5»), колонка REAL (миграция v7→v8).
    @ColumnInfo(name = "print_pages")
    val printPages: Double? = null,
    @ColumnInfo(name = "original_language")
    val originalLanguage: String? = null,
    @ColumnInfo(name = "original_title")
    val originalTitle: String? = null,
    @ColumnInfo(name = "annotation")
    val annotation: String? = null,
    @ColumnInfo(name = "is_gift")
    val isGift: Boolean = false,
    @ColumnInfo(name = "is_lent")
    val isLent: Boolean = false,
    @ColumnInfo(name = "is_sold")
    val isSold: Boolean = false,
)
