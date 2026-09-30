package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v2 → v3: add nullable purchase_date / purchase_price columns
 * for tracking where and when a book was bought.
 */
val Migrate2To3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN purchase_date INTEGER")
        db.execSQL("ALTER TABLE books ADD COLUMN purchase_price REAL")
    }
}
