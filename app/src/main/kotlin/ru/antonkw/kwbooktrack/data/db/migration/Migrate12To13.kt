package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Migration v12 → v13: даты перекодируются из полночи **локальной** зоны в
 * полночь **UTC**.
 *
 * Дата — это календарный день пользователя («прочитал 15 марта»), а не
 * момент времени. Пока она хранилась как полночь локальной зоны, смена
 * часового пояса телефона (переезд, настройка) сдвигала её на день: 15 марта
 * превращалось в 14-е. В полночи UTC дата перестаёт зависеть от телефона.
 *
 * Моменты времени (`created_at`, `updated_at`, начало сессии чтения) не
 * трогаем: это настоящие instants, их зона — зона устройства.
 *
 * Перекодирование идёт в ту же дату, которую пользователь видит сейчас:
 * старая полночь локальной зоны читается как дата в текущей зоне, и она же
 * записывается в UTC. Видимые даты не меняются — меняется только якорь.
 *
 * Ponytail: row-by-row UPDATE; строк в библиотеке — сотни, а наборный UPDATE
 * потребовал бы SQL-математики по датам, то есть ровно того, чего миграция
 * меняет.
 */
val Migrate12To13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        reencodeColumn(db, "books", "started_at")
        reencodeColumn(db, "books", "finished_at")
        reencodeColumn(db, "books", "purchase_date")
        reencodeColumn(db, "movies", "watched_at")
        reencodeColumn(db, "media_dates", "date")
    }

    private fun reencodeColumn(db: SupportSQLiteDatabase, table: String, column: String) {
        db.query("SELECT id, $column FROM $table WHERE $column IS NOT NULL AND $column > 0").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val oldMs = cursor.getLong(1)
                val date = Instant.ofEpochMilli(oldMs).atZone(ZoneId.systemDefault()).toLocalDate()
                val newMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
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
