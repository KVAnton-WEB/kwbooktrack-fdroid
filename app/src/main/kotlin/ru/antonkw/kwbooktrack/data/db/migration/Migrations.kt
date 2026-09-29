package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграция v1 → v2.
 *
 * Room автодетектит добавление колонок `title_search` / `author_search` (NOT NULL с DEFAULT ''),
 * но не умеет сам удалить virtual table FTS4 и связанные триггеры, которые были в v1
 * и пропали в v2 — поэтому AutoMigrationSpec указан явно.
 */
@DeleteTable.Entries(
    DeleteTable(tableName = "books_fts"),
)
class Migrate1To2 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        // v1 schema created six FTS4 content-sync triggers (BEFORE/AFTER ×
        // INSERT/UPDATE/DELETE); the v1→v2 migration only drops the virtual
        // table itself. Any orphaned trigger would block future DDL on the
        // `books` table. Drop them all by name pattern from sqlite_master.
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