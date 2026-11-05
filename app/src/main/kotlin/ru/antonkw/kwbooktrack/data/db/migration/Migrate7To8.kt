package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v7 → v8: `books.print_pages` (усл. печ. л.) становится REAL —
 * значение дробное («32,5»).
 *
 * SQLite не умеет менять тип колонки на месте, поэтому таблица пересоздаётся.
 * И тут есть подвох: на `books` висят четыре таблицы-потомка (notes,
 * reading_sessions, book_tags, photos) с `ON DELETE CASCADE`, а
 * `PRAGMA foreign_keys` внутри транзакции (а миграции Room идут в транзакции)
 * не выключается — `DROP TABLE books` стёр бы их содержимое.
 *
 * Поэтому сначала копируем строки потомков во временные таблицы, потом
 * пересоздаём `books` (каскад чистит оригиналы), потом возвращаем строки на
 * место. Всё внутри одной транзакции Room — при ошибке откатится целиком.
 */
val Migrate7To8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Страховка содержимого потомков.
        for (table in CHILDREN) {
            db.execSQL("CREATE TEMP TABLE `_${table}_bak` AS SELECT * FROM `$table`")
        }

        // 2. books с print_pages REAL.
        db.execSQL(CREATE_BOOKS)
        db.execSQL("INSERT INTO `books_new` ($COLUMNS) SELECT $COLUMNS FROM `books`")
        db.execSQL("DROP TABLE `books`")
        db.execSQL("ALTER TABLE `books_new` RENAME TO `books`")

        // 3. Потомки на место. photos последним: он ссылается на notes.
        for (table in CHILDREN) {
            db.execSQL("INSERT INTO `$table` SELECT * FROM `_${table}_bak`")
        }

        // 4. Индексы books — они исчезли вместе с таблицей.
        for (index in INDICES) {
            db.execSQL(index)
        }

        // 5. Страховка больше не нужна.
        for (table in CHILDREN) {
            db.execSQL("DROP TABLE `_${table}_bak`")
        }
    }
}

/** Таблицы с FK на books — их содержимое переживает пересоздание books. */
private val CHILDREN = listOf("notes", "reading_sessions", "book_tags", "photos")

private const val COLUMNS =
    "`id`, `title`, `subtitle`, `author`, `isbn13`, `page_count`, `language`, `genre`, " +
        "`publisher`, `year`, `cover_path`, `rating`, `status`, `started_at`, `finished_at`, " +
        "`created_at`, `updated_at`, `source`, `purchase_date`, `purchase_price`, " +
        "`title_search`, `author_search`, `series_search`, `binding`, `purchase_place`, " +
        "`series`, `series_number`, `condition`, `translator`, `print_year`, `print_pages`, " +
        "`original_language`, `original_title`, `annotation`, `is_gift`, `is_lent`, `is_sold`"

private const val CREATE_BOOKS =
    "CREATE TABLE `books_new` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, " +
        "`author` TEXT NOT NULL, `isbn13` TEXT, `page_count` INTEGER, `language` TEXT NOT NULL, " +
        "`genre` TEXT, `publisher` TEXT, `year` INTEGER, `cover_path` TEXT, `rating` INTEGER, " +
        "`status` TEXT NOT NULL, `started_at` INTEGER, `finished_at` INTEGER, " +
        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
        "`purchase_date` INTEGER, `purchase_price` REAL, `title_search` TEXT NOT NULL, " +
        "`author_search` TEXT NOT NULL, `series_search` TEXT NOT NULL, `binding` TEXT, " +
        "`purchase_place` TEXT, `series` TEXT, `series_number` TEXT, `condition` TEXT, " +
        "`translator` TEXT, `print_year` INTEGER, `print_pages` REAL, `original_language` TEXT, " +
        "`original_title` TEXT, `annotation` TEXT, `is_gift` INTEGER NOT NULL DEFAULT 0, " +
        "`is_lent` INTEGER NOT NULL DEFAULT 0, `is_sold` INTEGER NOT NULL DEFAULT 0)"

private val INDICES = listOf(
    "CREATE UNIQUE INDEX `index_books_isbn13` ON `books` (`isbn13`)",
    "CREATE INDEX `index_books_status` ON `books` (`status`)",
    "CREATE INDEX `index_books_started_at` ON `books` (`started_at`)",
    "CREATE INDEX `index_books_finished_at` ON `books` (`finished_at`)",
    "CREATE INDEX `index_books_purchase_date` ON `books` (`purchase_date`)",
    "CREATE INDEX `index_books_title_search` ON `books` (`title_search`)",
    "CREATE INDEX `index_books_author_search` ON `books` (`author_search`)",
)
