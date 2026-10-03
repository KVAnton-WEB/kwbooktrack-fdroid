package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Migration v6 → v7: dates that were historically encoded as
 * `LocalDate.atStartOfDay(UTC).toEpochMs()` are re-encoded as
 * `LocalDate.atStartOfDay(systemDefault).toEpochMs()`. The calendar date the
 * user sees is unchanged; only the zone anchor changes, so the new
 * system-zone decoder reads the same date back.
 *
 * Columns touched (date-bearing, with at least one importer that wrote them
 * as UTC midnight): books.finished_at / purchase_date,
 * reading_sessions.started_at / ended_at, notes.created_at / updated_at.
 *
 * Columns left alone: books.created_at / updated_at / started_at and
 * photos.created_at — those have only ever been `System.currentTimeMillis()`
 * wall-clock, so there is nothing to re-anchor.
 *
 * Ponytail: row-by-row UPDATE; library-scale row counts are tiny. A single
 * set-based UPDATE would need SQL-side LocalDate math, which is exactly the
 * broken thing this migration is moving away from.
 */
val Migrate6To7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        reencodeColumn(db, "books", "finished_at")
        reencodeColumn(db, "books", "purchase_date")
        reencodeColumn(db, "reading_sessions", "started_at")
        reencodeColumn(db, "reading_sessions", "ended_at")
        reencodeColumn(db, "notes", "created_at")
        reencodeColumn(db, "notes", "updated_at")
    }

    private fun reencodeColumn(db: SupportSQLiteDatabase, table: String, column: String) {
        db.query("SELECT id, $column FROM $table WHERE $column IS NOT NULL AND $column > 0").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val oldMs = cursor.getLong(1)
                val date = Instant.ofEpochMilli(oldMs).atZone(ZoneOffset.UTC).toLocalDate()
                val newMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (newMs != oldMs) {
                    db.execSQL(
                        "UPDATE $table SET $column = ? WHERE id = ?",
                        arrayOf<Any>(newMs, id),
                    )
                }
            }
        }
    }
}