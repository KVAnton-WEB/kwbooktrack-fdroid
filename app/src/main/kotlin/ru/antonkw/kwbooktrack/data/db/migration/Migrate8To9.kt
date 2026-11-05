package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v8 → v9: в `books` появляется колонка `extra_fields` — чужие
 * поля Tellico (пользовательские, которых нет в приложении) в JSON:
 * `{"<имя>":{"t":"<заголовок>","v":"<значение>"}}`.
 *
 * Колонка добавляется, таблица не пересоздаётся: на `books` висят четыре
 * таблицы-потомка (notes, reading_sessions, book_tags, photos) с
 * `ON DELETE CASCADE`, а пересоздание `books` стёрло бы их содержимое
 * (см. комментарий в [Migrate7To8]).
 *
 * Значение по умолчанию — `"{}"` (пустой набор чужих полей), поэтому
 * существующие книги остаются с пустыми полями.
 */
val Migrate8To9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `extra_fields` TEXT NOT NULL DEFAULT '{}'")
    }
}
