package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v1 → v2.
 *
 * Adds the `title_search` / `author_search` columns (NOT NULL DEFAULT '')
 * that the v2 BookEntity introduced for fast LIKE searches, and tears
 * down the FTS4 virtual table + content-sync triggers that v1 used to
 * keep those columns populated. Room can't auto-drop the triggers
 * because their existence isn't visible from the schema diff.
 */
val Migrate1To2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add the lowercase search columns Room auto-detects would have added.
        db.execSQL("ALTER TABLE books ADD COLUMN title_search TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE books ADD COLUMN author_search TEXT NOT NULL DEFAULT ''")
        // Drop the FTS4 virtual table that v1 maintained.
        db.execSQL("DROP TABLE IF EXISTS books_fts")
        // Drop any orphaned FTS content-sync triggers left on the books table
        // (BEFORE/AFTER × INSERT/UPDATE/DELETE = up to six). Pattern from
        // sqlite_master so we don't have to know exact names.
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'books' AND name LIKE 'room_fts_content_sync_books_fts_%'",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                db.execSQL("DROP TRIGGER IF EXISTS `$name`")
            }
        }
    }
}
