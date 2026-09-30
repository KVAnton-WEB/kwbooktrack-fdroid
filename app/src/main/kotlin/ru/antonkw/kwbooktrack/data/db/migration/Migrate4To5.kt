package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v4 → v5: extend the books table with the full Tellico field set.
 *
 * Each new column is added with the same type/nullability/default it has on
 * [ru.antonkw.kwbooktrack.data.db.entity.BookEntity], so reads stay identical
 * to a fresh insert. New rows inserted via the UI will start with NULL/0/false
 * until the user fills them in via Tellico import or the AddBook screen.
 *
 * Ponytail: ALTER TABLE ADD COLUMN is cheap on SQLite (<1ms per column at
 * the row counts we're dealing with); no need for the temporary-table
 * recreation that Room's AutoMigration emits for non-null defaults.
 */
val Migrate4To5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN binding TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN purchase_place TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN series TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN series_number TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN condition TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN translator TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN print_year INTEGER")
        db.execSQL("ALTER TABLE books ADD COLUMN print_pages INTEGER")
        db.execSQL("ALTER TABLE books ADD COLUMN original_language TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN original_title TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN annotation TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN is_gift INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN is_lent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN is_sold INTEGER NOT NULL DEFAULT 0")
    }
}
