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
 * Exercises [Migrate4To5] against a hand-crafted v4 schema.
 *
 * MigrationTestHelper wants an `Instrumentation` context which Robolectric
 * doesn't have. Instead we drive the migration ourselves: create the file
 * at v4 via a raw SupportSQLiteOpenHelper, then run `Migrate4To5.migrate(db)`
 * on its connection and verify the new columns land.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate4To5Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-4-to-5.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v4Schema = """
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
            `author_search` TEXT NOT NULL DEFAULT ''
        );
    """.trimIndent()

    private fun openV4Helper(): SupportSQLiteOpenHelper {
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(v4Schema)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op — the test never goes through Room's automatic
                            // upgrade path because we open v4 directly.
                        }
                    },
                )
                .build(),
        )
    }

    @Test
    fun migrate4To5AddsAll14Columns() {
        val helper = openV4Helper()
        try {
            // Trigger onCreate — the v4 schema is now live on disk.
            helper.writableDatabase.close()

            // Reopen, run the migration, verify each new column exists.
            val db = helper.writableDatabase
            Migrate4To5.migrate(db)

            val expected = listOf(
                "binding", "purchase_place", "series", "series_number",
                "condition", "translator", "print_year", "print_pages",
                "original_language", "original_title", "annotation",
                "is_gift", "is_lent", "is_sold",
            )
            val cols = mutableSetOf<String>()
            db.query("PRAGMA table_info(books)").use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
            for (col in expected) {
                assertThat(cols).contains(col)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrate4To5InsertsRowWithColumnDefaults() {
        val helper = openV4Helper()
        try {
            helper.writableDatabase.close()
            val db = helper.writableDatabase
            Migrate4To5.migrate(db)

            // Insert a v4-shape row (no v5 columns). Defaults must let this
            // through without NOT NULL failures.
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at, title_search, author_search) " +
                    "VALUES (1, 'Dune', 'Herbert', 'FINISHED', 0, 0, '', '')",
            )

            // Every v5 column must read back with a default — NULL for the
            // nullable ones, 0 for the booleans.
            val cursor = db.query(
                "SELECT binding, purchase_place, series, series_number, condition, " +
                    "translator, print_year, print_pages, original_language, original_title, " +
                    "annotation, is_gift, is_lent, is_sold FROM books WHERE id = 1",
            )
            cursor.use {
                assertThat(it.moveToFirst()).isTrue()
                for (i in 0..10) assertThat(it.isNull(i)).isTrue()
                assertThat(it.getInt(11)).isEqualTo(0)
                assertThat(it.getInt(12)).isEqualTo(0)
                assertThat(it.getInt(13)).isEqualTo(0)
            }
        } finally {
            helper.close()
        }
    }
}
