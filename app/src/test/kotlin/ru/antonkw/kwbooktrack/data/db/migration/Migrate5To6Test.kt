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
 * Exercises [Migrate5To6] against a hand-crafted v5 schema.
 *
 * Same approach as Migrate4To5Test: MigrationTestHelper needs an
 * `Instrumentation` context Robolectric doesn't have, so the v5 database is
 * created on disk via a raw SupportSQLiteOpenHelper and the migration is
 * driven by hand.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate5To6Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-5-to-6.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v5Schema = """
        CREATE TABLE `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `subtitle` TEXT,
            `author` TEXT NOT NULL,
            `isbn13` TEXT,
            `page_count` INTEGER,
            `language` TEXT NOT NULL DEFAULT 'ru',
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
            `source` TEXT NOT NULL DEFAULT 'MANUAL',
            `purchase_date` INTEGER,
            `purchase_price` REAL,
            `title_search` TEXT NOT NULL DEFAULT '',
            `author_search` TEXT NOT NULL DEFAULT '',
            `binding` TEXT,
            `purchase_place` TEXT,
            `series` TEXT,
            `series_number` TEXT,
            `condition` TEXT,
            `translator` TEXT,
            `print_year` INTEGER,
            `print_pages` INTEGER,
            `original_language` TEXT,
            `original_title` TEXT,
            `annotation` TEXT,
            `is_gift` INTEGER NOT NULL DEFAULT 0,
            `is_lent` INTEGER NOT NULL DEFAULT 0,
            `is_sold` INTEGER NOT NULL DEFAULT 0
        );
    """.trimIndent()

    private fun openV5Helper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(v5Schema)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op — the test never goes through Room's automatic
                            // upgrade path because we open v5 directly.
                        }
                    },
                )
                .build(),
        )

    private fun seriesSearchOf(db: SupportSQLiteDatabase, id: Long): String =
        db.query("SELECT series_search FROM books WHERE id = $id").use {
            it.moveToFirst()
            it.getString(0)
        }

    @Test
    fun migrate5To6AddsSeriesSearchColumn() {
        val helper = openV5Helper()
        try {
            helper.writableDatabase.close()
            val db = helper.writableDatabase
            Migrate5To6.migrate(db)

            val cols = mutableSetOf<String>()
            db.query("PRAGMA table_info(books)").use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            assertThat(cols).contains("series_search")
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrate5To6BackfillsCyrillicSeriesInLowercase() {
        val helper = openV5Helper()
        try {
            helper.writableDatabase.close()
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at, " +
                    "series, series_number) VALUES (1, 'Братство', 'Робинс', 'FINISHED', 0, 0, " +
                    "'Стиль Сага', '7')",
            )
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at, " +
                    "series, series_number) VALUES (2, 'Дюна', 'Герберт', 'FINISHED', 0, 0, " +
                    "'Dune Chronicles', '1')",
            )
            // Blank-but-non-null series: nothing searchable, stays empty.
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at, " +
                    "series, series_number) VALUES (3, 'Без серии', 'Никто', 'FINISHED', 0, 0, " +
                    "'   ', NULL)",
            )

            Migrate5To6.migrate(db)

            // Kotlin lowercased it, not SQLite — a SQL lower() would have left
            // the Cyrillic untouched on a real device.
            assertThat(seriesSearchOf(db, 1)).isEqualTo("стиль сага 7")
            assertThat(seriesSearchOf(db, 2)).isEqualTo("dune chronicles 1")
            assertThat(seriesSearchOf(db, 3)).isEmpty()
        } finally {
            helper.close()
        }
    }
}
