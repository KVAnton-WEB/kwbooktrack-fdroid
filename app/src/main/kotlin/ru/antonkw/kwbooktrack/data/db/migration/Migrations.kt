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
        // Триггеры удаляем первыми, чтобы они не висели на удалённой таблице.
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_books_fts_BEFORE_UPDATE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_books_fts_BEFORE_DELETE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_books_fts_AFTER_UPDATE")
        db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_books_fts_AFTER_INSERT")
    }
}