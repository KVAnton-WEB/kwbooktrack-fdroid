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
 * Exercises [Migrate7To8] against a hand-crafted v7 schema. Same approach as
 * [Migrate6To7Test]: open a raw SupportSQLiteOpenHelper at v7, drive the
 * migration by hand, then read back.
 *
 * Главное в этой миграции — не потерять содержимое таблиц-потомков: у всех
 * четырёх `ON DELETE CASCADE` на `books`, а пересоздание `books` их сносит.
 * Поэтому в тесте есть строки во всех потомках, а `PRAGMA foreign_keys`
 * включён — иначе каскад не сработал бы и тест прошёл бы вхолостую.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate7To8Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-7-to-8.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v7Schema = listOf(
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
            `print_pages` INTEGER,
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
        """
        CREATE TABLE `reading_sessions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL,
            `started_at` INTEGER NOT NULL,
            `ended_at` INTEGER,
            `start_page` INTEGER,
            `end_page` INTEGER,
            `duration_sec` INTEGER,
            `note` TEXT,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE `photos` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL,
            `note_id` INTEGER,
            `path` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE TABLE `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)",
        """
        CREATE TABLE `book_tags` (
            `book_id` INTEGER NOT NULL,
            `tag_id` INTEGER NOT NULL,
            PRIMARY KEY(`book_id`, `tag_id`),
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX `index_notes_book_id` ON `notes` (`book_id`)",
        "CREATE INDEX `index_reading_sessions_book_id` ON `reading_sessions` (`book_id`)",
        "CREATE INDEX `index_photos_book_id` ON `photos` (`book_id`)",
        "CREATE INDEX `index_book_tags_book_id` ON `book_tags` (`book_id`)",
    )

    private fun openV7Helper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            // Без этого каскад не сработает и тест ничего не проверяет.
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in v7Schema) db.execSQL(stmt)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op: test opens v7 directly.
                        }
                    },
                )
                .build(),
        )

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO books (id, title, author, status, language, source, created_at, updated_at, " +
                "title_search, author_search, series_search, print_pages) " +
                "VALUES (1, 'Норвежский лес', 'Мураками', 'FINISHED', 'ru', 'MANUAL', 0, 0, '', '', '', 32)",
        )
        db.execSQL(
            "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                "VALUES (10, 1, 'мысль', 'NOTE', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO reading_sessions (id, book_id, started_at, ended_at) VALUES (20, 1, 100, 200)",
        )
        db.execSQL("INSERT INTO tags (id, name) VALUES (30, 'тэг')")
        db.execSQL("INSERT INTO book_tags (book_id, tag_id) VALUES (1, 30)")
        db.execSQL(
            "INSERT INTO photos (id, book_id, note_id, path, created_at) VALUES (40, 1, 10, '/tmp/a.jpg', 0)",
        )
    }

    @Test
    fun foreignKeysAreEnforcedSoTheBackupIsNotSuperfluous() {
        // Страховка в миграции нужна только если каскад реально срабатывает.
        val helper = openV7Helper()
        try {
            val db = helper.writableDatabase
            seed(db)
            db.execSQL("DELETE FROM books WHERE id = 1")
            assertThat(count(db, "notes")).isEqualTo(0)
            assertThat(count(db, "photos")).isEqualTo(0)
        } finally {
            helper.close()
        }
    }

    @Test
    fun printPagesColumnBecomesReal() {
        val helper = openV7Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate7To8.migrate(db)

            val type = db.query("PRAGMA table_info(books)").use { c ->
                var found = ""
                while (c.moveToNext()) {
                    if (c.getString(1) == "print_pages") found = c.getString(2)
                }
                found
            }
            assertThat(type).isEqualTo("REAL")
            db.query("SELECT print_pages FROM books WHERE id = 1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getDouble(0)).isEqualTo(32.0)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun childrenRowsSurviveTheBooksRebuild() {
        val helper = openV7Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate7To8.migrate(db)

            assertThat(count(db, "books")).isEqualTo(1)
            assertThat(count(db, "notes")).isEqualTo(1)
            assertThat(count(db, "reading_sessions")).isEqualTo(1)
            assertThat(count(db, "book_tags")).isEqualTo(1)
            assertThat(count(db, "photos")).isEqualTo(1)
            // photos.note_id ссылается на notes — при каскаде он обнулился бы.
            db.query("SELECT note_id FROM photos WHERE id = 40").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(10)
            }
            db.query("SELECT body_md FROM notes WHERE id = 10").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("мысль")
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun booksIndicesAreRecreated() {
        val helper = openV7Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate7To8.migrate(db)

            val indices = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'books'")
                .use { c ->
                    while (c.moveToNext()) indices.add(c.getString(0))
                }
            assertThat(indices).containsAtLeast(
                "index_books_isbn13",
                "index_books_status",
                "index_books_started_at",
                "index_books_finished_at",
                "index_books_purchase_date",
                "index_books_title_search",
                "index_books_author_search",
            )
        } finally {
            helper.close()
        }
    }
}
