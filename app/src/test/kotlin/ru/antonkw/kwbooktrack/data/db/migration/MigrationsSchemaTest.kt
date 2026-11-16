package ru.antonkw.kwbooktrack.data.db.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase

/**
 * Проверка того, что база, созданная старой версией приложения, после миграций
 * открывается **самим Room** и проходит проверку схемы.
 *
 * Остальные тесты миграций гоняют `Migration.migrate(db)` руками и смотрят
 * на `PRAGMA table_info`. Этого мало: Room при открытии сверяет схему с
 * описанием сущностей и, если не сошлось, падает с «Room cannot verify the
 * data integrity» — у пользователя с обновлённой базой приложение просто не
 * запустится. Сверить схему может только сам Room.
 *
 * Здесь база создаётся руками на версии 9, потом её открывает Room с
 * миграциями — так же, как это сделает приложение при обновлении.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationsSchemaTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "test-migrations-schema.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    /**
     * Схема версии 9 — как её создавал Room на той версии приложения.
     * Должна совпадать с описанием сущностей: иначе Room не примет результат
     * миграций.
     */
    private val v9Schema = listOf(
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
            `is_sold` INTEGER NOT NULL DEFAULT 0,
            `extra_fields` TEXT NOT NULL DEFAULT '{}'
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
        """
        CREATE TABLE `tags` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE `book_tags` (
            `book_id` INTEGER NOT NULL,
            `tag_id` INTEGER NOT NULL,
            PRIMARY KEY(`book_id`, `tag_id`),
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX `index_books_isbn13` ON `books` (`isbn13`)",
        "CREATE INDEX `index_books_status` ON `books` (`status`)",
        "CREATE INDEX `index_books_started_at` ON `books` (`started_at`)",
        "CREATE INDEX `index_books_finished_at` ON `books` (`finished_at`)",
        "CREATE INDEX `index_books_purchase_date` ON `books` (`purchase_date`)",
        "CREATE INDEX `index_books_title_search` ON `books` (`title_search`)",
        "CREATE INDEX `index_books_author_search` ON `books` (`author_search`)",
        "CREATE INDEX `index_reading_sessions_book_id` ON `reading_sessions` (`book_id`)",
        "CREATE INDEX `index_reading_sessions_started_at` ON `reading_sessions` (`started_at`)",
        "CREATE INDEX `index_notes_book_id` ON `notes` (`book_id`)",
        "CREATE INDEX `index_notes_created_at` ON `notes` (`created_at`)",
        "CREATE INDEX `index_notes_kind` ON `notes` (`kind`)",
        "CREATE INDEX `index_photos_book_id` ON `photos` (`book_id`)",
        "CREATE INDEX `index_photos_note_id` ON `photos` (`note_id`)",
        "CREATE UNIQUE INDEX `index_tags_name` ON `tags` (`name`)",
        "CREATE INDEX `index_book_tags_book_id` ON `book_tags` (`book_id`)",
        "CREATE INDEX `index_book_tags_tag_id` ON `book_tags` (`tag_id`)",
    )

    private fun createV9Database() {
        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in v9Schema) db.execSQL(stmt)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op: база создаётся сразу на версии 9.
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, author, status, language, source, created_at, updated_at, " +
                    "title_search, author_search, series_search, extra_fields) " +
                    "VALUES (1, 'Норвежский лес', 'Мураками', 'FINISHED', 'ru', 'MANUAL', 0, 0, '', '', '', '{}')",
            )
            db.execSQL(
                "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                    "VALUES (10, 1, 'мысль', 'NOTE', 0, 0)",
            )
        }
        helper.close()
    }

    @Test
    fun roomOpensMigratedDatabaseAndKeepsRows() = runBlocking {
        createV9Database()

        val db = Room.databaseBuilder(ctx, KwBooktrackDatabase::class.java, dbName)
            .addMigrations(Migrate9To10, Migrate10To11)
            .build()
        try {
            // Первый запрос открывает базу: Room проверяет схему и, если она
            // не совпала с описанием сущностей, падает здесь.
            assertThat(db.bookDao().count()).isEqualTo(1)
            assertThat(db.noteDao().getAll()).hasSize(1)
            assertThat(db.noteDao().getAll().single().mediaId).isEqualTo(1L)
            assertThat(db.noteDao().getAll().single().mediaType.name).isEqualTo("BOOKS")
            assertThat(db.movieDao().count()).isEqualTo(0)
        } finally {
            db.close()
        }
    }
}
