package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v5 → v6: add the pre-lowercased `series_search` column so series
 * is searchable over Cyrillic.
 *
 * AOSP's SQLite folds case for ASCII only (no ICU), so `LOWER(series) LIKE
 * '%сага%'` cannot match a capitalized Cyrillic series. Same reason
 * `title_search` / `author_search` exist. BookRepository.withSearchKeys keeps
 * the column populated from here on.
 *
 * The backfill runs through Kotlin's `lowercase()` rather than SQL `lower()`,
 * precisely because SQL's version is ASCII-only. Existing rows would
 * otherwise come out with an English-letter series cased and a Cyrillic one
 * untouched, which is the bug this column exists to remove.
 *
 * ponytail: row-by-row UPDATE inside the migration transaction. At the row
 * counts a personal library holds (hundreds to low thousands) this is a few
 * ms; a single set-based UPDATE would need the lowercasing done in SQL, which
 * is the thing that doesn't work.
 */
val Migrate5To6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN series_search TEXT NOT NULL DEFAULT ''")
        db.query("SELECT id, series, series_number FROM books WHERE series IS NOT NULL OR series_number IS NOT NULL")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val text = listOfNotNull(cursor.getString(1), cursor.getString(2))
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { it.lowercase() }
                    if (text.isNotEmpty()) {
                        db.execSQL(
                            "UPDATE books SET series_search = ? WHERE id = ?",
                            arrayOf<Any>(text, cursor.getLong(0)),
                        )
                    }
                }
            }
    }
}
