package ru.antonkw.kwbooktrack.data.db.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.time.toLocalDayStartMs

/**
 * Exercises [Migrate6To7] against a hand-crafted v6 schema. Same approach as
 * [Migrate5To6Test]: open a raw SupportSQLiteOpenHelper at v6, drive the
 * migration by hand, then read back.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate6To7Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-6-to-7.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v6SchemaStatements = listOf(
        """
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
            `series_search` TEXT NOT NULL DEFAULT '',
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
    )

    private fun openV6Helper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in v6SchemaStatements) db.execSQL(stmt)
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op: test opens v6 directly.
                        }
                    },
                )
                .build(),
        )

    private fun longOf(db: SupportSQLiteDatabase, table: String, id: Long, column: String): Long? =
        db.query("SELECT $column FROM $table WHERE id = $id").use { c ->
            if (!c.moveToFirst()) null else if (c.isNull(0)) null else c.getLong(0)
        }

    @Test
    fun reencodePreservesCalendarDateForUtcMidnightInput() {
        // finished_at was stored as UTC midnight of "2026-09-15" (the user-entered
        // date in Bookmory). After the migration it should read back as the same
        // calendar date when decoded with the new system-zone decoder.
        val helper = openV6Helper()
        try {
            val db = helper.writableDatabase
            val date = LocalDate.of(2026, 9, 15)
            val utcMidnight = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at, finished_at, purchase_date) " +
                    "VALUES (1, 'Норвежский лес', 'Мураками', 'FINISHED', 0, 0, $utcMidnight, $utcMidnight)",
            )

            Migrate6To7.migrate(db)

            val expected = date.toLocalDayStartMs()
            assertThat(longOf(db, "books", 1, "finished_at")).isEqualTo(expected)
            assertThat(longOf(db, "books", 1, "purchase_date")).isEqualTo(expected)
        } finally {
            helper.close()
        }
    }

    @Test
    fun reencodeSkipsNullAndZeroColumns() {
        // NULL or 0 values: nothing to re-encode, must stay NULL/0.
        val helper = openV6Helper()
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at) " +
                    "VALUES (1, 'Без дат', 'Тест', 'WISHLIST', 0, 0)",
            )
            Migrate6To7.migrate(db)
            assertThat(longOf(db, "books", 1, "finished_at")).isNull()
            assertThat(longOf(db, "books", 1, "purchase_date")).isNull()
        } finally {
            helper.close()
        }
    }

    @Test
    fun reencodeTouchesReadingSessionsAndNotes() {
        val helper = openV6Helper()
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at) " +
                    "VALUES (1, 'Книга', 'Автор', 'READING', 0, 0)",
            )
            val sessionStart = LocalDate.of(2026, 5, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val sessionEnd = LocalDate.of(2026, 5, 10).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val noteCreated = LocalDate.of(2026, 5, 5).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            db.execSQL(
                "INSERT INTO reading_sessions (id, book_id, started_at, ended_at) VALUES (10, 1, $sessionStart, $sessionEnd)",
            )
            db.execSQL(
                "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                    "VALUES (20, 1, 'мысль', 'NOTE', $noteCreated, $noteCreated)",
            )

            Migrate6To7.migrate(db)

            assertThat(longOf(db, "reading_sessions", 10, "started_at"))
                .isEqualTo(LocalDate.of(2026, 5, 1).toLocalDayStartMs())
            assertThat(longOf(db, "reading_sessions", 10, "ended_at"))
                .isEqualTo(LocalDate.of(2026, 5, 10).toLocalDayStartMs())
            assertThat(longOf(db, "notes", 20, "created_at"))
                .isEqualTo(LocalDate.of(2026, 5, 5).toLocalDayStartMs())
            assertThat(longOf(db, "notes", 20, "updated_at"))
                .isEqualTo(LocalDate.of(2026, 5, 5).toLocalDayStartMs())
        } finally {
            helper.close()
        }
    }

    @Test
    fun reencodeDoesNotTouchWallClockColumns() {
        // books.created_at / books.updated_at are wall-clock millis, not
        // midnight-encoded. Migration leaves them alone.
        val helper = openV6Helper()
        try {
            val db = helper.writableDatabase
            val wallClock = 1_700_000_000_000L
            db.execSQL(
                "INSERT INTO books (id, title, author, status, created_at, updated_at) " +
                    "VALUES (1, 'Книга', 'Автор', 'READING', $wallClock, $wallClock)",
            )
            Migrate6To7.migrate(db)
            assertThat(longOf(db, "books", 1, "created_at")).isEqualTo(wallClock)
            assertThat(longOf(db, "books", 1, "updated_at")).isEqualTo(wallClock)
        } finally {
            helper.close()
        }
    }
}