package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v13 → v14: убираем индексы по поисковым колонкам.
 *
 * Поиск идёт как `title_search LIKE '%слово%'`, а такой LIKE индекс не
 * использует (индекс работает только для LIKE по началу строки). Индексы
 * не давали ничего, но платили за собой на каждой записи.
 *
 * Данных не трогаем: колонки `title_search` и `author_search` остаются как
 * были — их по-прежнему заполняет репозиторий при записи.
 */
val Migrate13To14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_books_title_search`")
        db.execSQL("DROP INDEX IF EXISTS `index_books_author_search`")
    }
}
