package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v11 → v12: таблица дополнительных дат просмотра и прочтения.
 *
 * Данных переносить нечего: основные даты остаются в своих колонках, а
 * дополнительных ещё нет. Таблица ни на что не ссылается, поэтому порядок
 * пересоздания таблиц (как в 9 → 10) здесь не нужен.
 */
val Migrate11To12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_dates` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`media_id` INTEGER NOT NULL, " +
                "`media_type` TEXT NOT NULL, " +
                "`date` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_dates_media_id` ON `media_dates` (`media_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_dates_media_type` ON `media_dates` (`media_type`)")
    }
}
