package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v3 → v4: index on notes(kind) so observeByKind / countByKind
 * become O(log n) instead of a full scan. CREATE INDEX IF NOT EXISTS is
 * idempotent so re-running on a partially-migrated DB is safe.
 */
val Migrate3To4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_kind ON notes(kind)")
    }
}
