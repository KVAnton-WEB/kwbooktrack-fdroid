package ru.antonkw.kwbooktrack.data.db.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.data.time.toDayMs
import ru.antonkw.kwbooktrack.data.time.toLocalDayStartMs
import java.time.LocalDate

/**
 * Migration v12 → v13: дата переезжает из полночи локальной зоны в полночь
 * UTC — и **видимая дата не меняется**.
 *
 * Если бы дата съехала на день, пользователь обнаружил бы это в своей
 * библиотеке: «прочитал 15 марта» превратилось бы в 14-е.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate12To13Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-12-to-13.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    /** Только те таблицы и колонки, которых касается миграция. */
    private val v12Schema = listOf(
        """
        CREATE TABLE `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `author` TEXT NOT NULL,
            `language` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `started_at` INTEGER,
            `finished_at` INTEGER,
            `purchase_date` INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE `movies` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `year` INTEGER,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `watched_at` INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE `media_dates` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `media_id` INTEGER NOT NULL,
            `media_type` TEXT NOT NULL,
            `date` INTEGER NOT NULL
        )
        """.trimIndent(),
    )

    @Test
    fun datesKeepTheirCalendarDay() {
        val helper = openV12()
        try {
            helper.writableDatabase.use { db ->
                val day = LocalDate.of(2026, 5, 10)
                val localMidnight = day.toLocalDayStartMs()
                db.execSQL(
                    "INSERT INTO books (id, title, author, language, status, created_at, updated_at, source, " +
                        "started_at, finished_at, purchase_date) " +
                        "VALUES (1, 'Дюна', 'Герберт', 'ru', 'FINISHED', 0, 0, 'MANUAL', ?, ?, ?)",
                    arrayOf<Any>(localMidnight, localMidnight, localMidnight),
                )
                db.execSQL(
                    "INSERT INTO movies (id, title, created_at, updated_at, source, watched_at) " +
                        "VALUES (1, 'Дюна', 0, 0, 'MANUAL', ?)",
                    arrayOf<Any>(localMidnight),
                )
                db.execSQL(
                    "INSERT INTO media_dates (id, media_id, media_type, date) VALUES (1, 1, 'BOOKS', ?)",
                    arrayOf<Any>(localMidnight),
                )

                Migrate12To13.migrate(db)

                // Видимая дата — та же, а значение теперь полночь UTC.
                assertThat(longOf(db, "books", 1, "started_at")).isEqualTo(day.toDayMs())
                assertThat(longOf(db, "books", 1, "finished_at")).isEqualTo(day.toDayMs())
                assertThat(longOf(db, "books", 1, "purchase_date")).isEqualTo(day.toDayMs())
                assertThat(longOf(db, "movies", 1, "watched_at")).isEqualTo(day.toDayMs())
                assertThat(longOf(db, "media_dates", 1, "date")).isEqualTo(day.toDayMs())
                // И читается обратно в ту же дату.
                assertThat(longOf(db, "books", 1, "finished_at").toDayDate()).isEqualTo(day)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun nullDatesAreLeftAlone() {
        val helper = openV12()
        try {
            helper.writableDatabase.use { db ->
                db.execSQL(
                    "INSERT INTO books (id, title, author, language, status, created_at, updated_at, source) " +
                        "VALUES (1, 'Дюна', 'Герберт', 'ru', 'WISHLIST', 0, 0, 'MANUAL')",
                )
                Migrate12To13.migrate(db)
                assertThat(longOf(db, "books", 1, "finished_at")).isEqualTo(0L)
            }
        } finally {
            helper.close()
        }
    }

    private fun openV12(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            v12Schema.forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // База создаётся сразу на версии 12.
                        }
                    },
                )
                .build(),
        )

    private fun longOf(db: SupportSQLiteDatabase, table: String, id: Long, column: String): Long =
        db.query("SELECT $column FROM $table WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
            check(cursor.moveToFirst()) { "нет строки $table#$id" }
            cursor.getLong(0)
        }
}
