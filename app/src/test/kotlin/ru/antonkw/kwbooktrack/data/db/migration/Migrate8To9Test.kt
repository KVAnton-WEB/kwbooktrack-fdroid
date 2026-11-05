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

/**
 * Exercises [Migrate8To9] against a hand-crafted v8 schema. Same approach as
 * [Migrate7To8Test]: open a raw SupportSQLiteOpenHelper at v8, drive the
 * migration by hand, then read back.
 *
 * Миграция добавляет колонку `books.extra_fields` (чужие поля Tellico в
 * JSON). Таблица не пересоздаётся — на `books` висят четыре таблицы-потомка
 * с `ON DELETE CASCADE`, и пересоздание `books` стёрло бы их содержимое
 * (см. комментарий в [Migrate7To8]). Поэтому в тесте есть строка в потомке,
 * а `PRAGMA foreign_keys` включён — иначе каскад не сработал бы и тест
 * прошёл бы вхолостую.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate8To9Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-8-to-9.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v8Schema = listOf(
        """
        CREATE TABLE `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `subtitle` TEXT,
            `author` TEXT NOT NULL,
            `isbn13` TEXT,
            `page_count` INTEGER,
            `language` TEXT NOT NULL,
            `genre` TEXT,
            `publisher` TEXT,
            `year` INTEGER,
            `cover_path` TEXT,
            `rating` INTEGER,
            `status` TEXT NOT NULL,
            `started_at` INTEGER,
            `finished_at` INTEGER,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `purchase_date` INTEGER,
            `purchase_price` REAL,
            `title_search` TEXT NOT NULL,
            `author_search` TEXT NOT NULL,
            `series_search` TEXT NOT NULL,
            `binding` TEXT,
            `purchase_place` TEXT,
            `series` TEXT,
            `series_number` TEXT,
            `condition` TEXT,
            `translator` TEXT,
            `print_year` INTEGER,
            `print_pages` REAL,
            `original_language` TEXT,
            `original_title` TEXT,
            `annotation` TEXT,
            `is_gift` INTEGER NOT NULL DEFAULT 0,
            `is_lent` INTEGER NOT NULL DEFAULT 0,
            `is_sold` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE `notes` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL,
            `page` INTEGER,
            `body_md` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    private fun openV8Helper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            // Без этого каскад не сработал бы и тест ничего не проверял бы.
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in v8Schema) db.execSQL(stmt)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op: test opens v8 directly.
                        }
                    },
                )
                .build(),
        )

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO books (id, title, author, status, language, source, created_at, updated_at, " +
                "title_search, author_search, series_search, print_pages) " +
                "VALUES (1, 'Норвежский лес', 'Мураками', 'FINISHED', 'ru', 'MANUAL', 0, 0, '', '', '', 32.5)",
        )
        db.execSQL(
            "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                "VALUES (10, 1, 'мысль', 'NOTE', 0, 0)",
        )
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    @Test
    fun extraFieldsColumnIsAdded() {
        val helper = openV8Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate8To9.migrate(db)

            val type = db.query("PRAGMA table_info(books)").use { c ->
                var found = ""
                while (c.moveToNext()) {
                    if (c.getString(1) == "extra_fields") found = c.getString(2)
                }
                found
            }
            assertThat(type).isEqualTo("TEXT")
        } finally {
            helper.close()
        }
    }

    @Test
    fun existingBookKeepsItsRowAndGetsEmptyExtraFields() {
        val helper = openV8Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate8To9.migrate(db)

            db.query("SELECT title, print_pages, extra_fields FROM books WHERE id = 1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("Норвежский лес")
                assertThat(c.getDouble(1)).isEqualTo(32.5)
                assertThat(c.getString(2)).isEqualTo("{}")
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun childrenRowsSurviveTheMigration() {
        // Миграция добавляет колонку, а не пересоздаёт таблицу: содержимое
        // потомков с `ON DELETE CASCADE` должно остаться на месте.
        val helper = openV8Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate8To9.migrate(db)

            assertThat(count(db, "books")).isEqualTo(1)
            assertThat(count(db, "notes")).isEqualTo(1)
            db.query("SELECT body_md FROM notes WHERE id = 10").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("мысль")
            }
        } finally {
            helper.close()
        }
    }
}
