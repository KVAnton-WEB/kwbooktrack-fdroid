package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v10 → v11: появляется таблица `movies` — фильмы.
 *
 * Данных переносить нечего: таблица новая и пустая, фильмы приезжают из
 * импорта. `books` и всё остальное не трогаем.
 */
val Migrate10To11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_MOVIES)
        for (index in INDICES) db.execSQL(index)
    }
}

private const val CREATE_MOVIES =
    "CREATE TABLE IF NOT EXISTS `movies` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
        "`original_title` TEXT, `year` INTEGER, `genre` TEXT, `nationality` TEXT, " +
        "`director` TEXT, `cast` TEXT, `duration_min` INTEGER, `rating` INTEGER, " +
        "`watched_at` INTEGER, `cover_path` TEXT, `plot` TEXT, `comments` TEXT, " +
        "`url` TEXT, `is_downloaded` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
        "`updated_at` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
        "`title_search` TEXT NOT NULL, `people_search` TEXT NOT NULL, " +
        "`extra_fields` TEXT NOT NULL)"

private val INDICES = listOf(
    "CREATE INDEX `index_movies_title_search` ON `movies` (`title_search`)",
    "CREATE INDEX `index_movies_people_search` ON `movies` (`people_search`)",
    "CREATE INDEX `index_movies_watched_at` ON `movies` (`watched_at`)",
)
