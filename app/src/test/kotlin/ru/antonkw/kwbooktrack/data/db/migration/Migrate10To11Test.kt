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
 * Exercises [Migrate10To11] against a hand-crafted v10 schema.
 *
 * v10 — это схема после [Migrate9To10]: заметки, фото и связи тегов уже
 * media-скоуповые. Миграция добавляет рядом таблицу `movies` и ничего не
 * трогает, поэтому проверяем, что фильмы появились, а книги и заметки
 * остались на месте.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate10To11Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-10-to-11.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v10Schema = listOf(
        """
        CREATE TABLE `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `author` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `language` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `title_search` TEXT NOT NULL,
            `author_search` TEXT NOT NULL,
            `series_search` TEXT NOT NULL,
            `extra_fields` TEXT NOT NULL DEFAULT '{}'
        )
        """.trimIndent(),
        """
        CREATE TABLE `notes` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `media_id` INTEGER NOT NULL,
            `media_type` TEXT NOT NULL,
            `page` INTEGER,
            `body_md` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE `tags` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE `media_tags` (
            `media_id` INTEGER NOT NULL,
            `media_type` TEXT NOT NULL,
            `tag_id` INTEGER NOT NULL,
            PRIMARY KEY(`media_id`, `media_type`, `tag_id`),
            FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    private fun openV10Helper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in v10Schema) db.execSQL(stmt)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op: test opens v10 directly.
                        }
                    },
                )
                .build(),
        )

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO books (id, title, author, status, language, source, created_at, updated_at, " +
                "title_search, author_search, series_search, extra_fields) " +
                "VALUES (1, 'Норвежский лес', 'Мураками', 'FINISHED', 'ru', 'MANUAL', 0, 0, '', '', '', '{}')",
        )
        db.execSQL(
            "INSERT INTO notes (id, media_id, media_type, body_md, kind, created_at, updated_at) " +
                "VALUES (10, 1, 'BOOKS', 'мысль', 'NOTE', 0, 0)",
        )
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(1)
            names
        }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    @Test
    fun migrationCreatesMoviesTable() {
        val helper = openV10Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate10To11.migrate(db)

            assertThat(columns(db, "movies")).containsExactly(
                "id", "title", "original_title", "year", "genre", "nationality", "director",
                "cast", "duration_min", "rating", "watched_at", "cover_path", "plot",
                "comments", "url", "is_downloaded", "created_at", "updated_at", "source",
                "title_search", "people_search", "extra_fields",
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationCreatesMoviesIndices() {
        val helper = openV10Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate10To11.migrate(db)

            val indexes = db.query("PRAGMA index_list(`movies`)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(1)
                names
            }
            assertThat(indexes).containsAtLeast(
                "index_movies_title_search",
                "index_movies_people_search",
                "index_movies_watched_at",
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationKeepsExistingRows() {
        val helper = openV10Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate10To11.migrate(db)

            assertThat(count(db, "books")).isEqualTo(1)
            assertThat(count(db, "notes")).isEqualTo(1)
            assertThat(count(db, "movies")).isEqualTo(0)
            db.query("SELECT body_md FROM notes WHERE id = 10").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("мысль")
            }
        } finally {
            helper.close()
        }
    }
}
