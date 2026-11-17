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
 * Вся цепочка миграций от самой первой версии — и проверка **самим Room**.
 *
 * Пошаговые тесты гоняют `Migration.migrate(db)` руками и смотрят на
 * `PRAGMA table_info`. Этого мало: Room при открытии сверяет схему с
 * описанием сущностей и, если не сошлось, падает с «Room cannot verify the
 * data integrity» — у пользователя с обновлённой базой приложение просто не
 * запустится. Сверить схему может только сам Room.
 *
 * Схема версии 1 — как её создавал Room на той версии приложения
 * (`app/schemas/.../1.json`).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationsFromV1Test {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "test-migrations-from-v1.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v1Schema = listOf(
        """
        CREATE TABLE IF NOT EXISTS `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL, `subtitle` TEXT, `author` TEXT NOT NULL,
            `isbn13` TEXT, `page_count` INTEGER, `language` TEXT NOT NULL,
            `genre` TEXT, `publisher` TEXT, `year` INTEGER, `cover_path` TEXT,
            `rating` INTEGER, `status` TEXT NOT NULL, `started_at` INTEGER,
            `finished_at` INTEGER, `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL, `source` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `books_fts` USING FTS4(
            `title` TEXT NOT NULL, `subtitle` TEXT, `author` TEXT NOT NULL,
            `isbn13` TEXT, `genre` TEXT, `publisher` TEXT, content=`books`
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `reading_sessions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL, `started_at` INTEGER NOT NULL,
            `ended_at` INTEGER, `start_page` INTEGER, `end_page` INTEGER,
            `duration_sec` INTEGER, `note` TEXT,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `notes` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL, `page` INTEGER, `body_md` TEXT NOT NULL,
            `kind` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `photos` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL, `note_id` INTEGER, `path` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `tags` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `book_tags` (
            `book_id` INTEGER NOT NULL, `tag_id` INTEGER NOT NULL,
            PRIMARY KEY(`book_id`, `tag_id`),
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        // Индексы версии 1 — из `app/schemas/.../1.json`: без них Room при
        // открытии не сойдётся с описанием сущностей.
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_books_isbn13` ON `books` (`isbn13`)",
        "CREATE INDEX IF NOT EXISTS `index_books_status` ON `books` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_books_started_at` ON `books` (`started_at`)",
        "CREATE INDEX IF NOT EXISTS `index_books_finished_at` ON `books` (`finished_at`)",
        "CREATE INDEX IF NOT EXISTS `index_reading_sessions_book_id` ON `reading_sessions` (`book_id`)",
        "CREATE INDEX IF NOT EXISTS `index_reading_sessions_started_at` ON `reading_sessions` (`started_at`)",
        "CREATE INDEX IF NOT EXISTS `index_notes_book_id` ON `notes` (`book_id`)",
        "CREATE INDEX IF NOT EXISTS `index_notes_created_at` ON `notes` (`created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_photos_book_id` ON `photos` (`book_id`)",
        "CREATE INDEX IF NOT EXISTS `index_photos_note_id` ON `photos` (`note_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)",
        "CREATE INDEX IF NOT EXISTS `index_book_tags_book_id` ON `book_tags` (`book_id`)",
        "CREATE INDEX IF NOT EXISTS `index_book_tags_tag_id` ON `book_tags` (`tag_id`)",
    )

    @Test
    fun roomOpensDatabaseCreatedByVersion1AndKeepsRows() = runBlocking {
        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            v1Schema.forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // База создаётся сразу на версии 1.
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, author, language, status, created_at, updated_at, source) " +
                    "VALUES (1, 'Норвежский лес', 'Мураками', 'ru', 'FINISHED', 0, 0, 'MANUAL')",
            )
            db.execSQL(
                "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                    "VALUES (10, 1, 'мысль', 'NOTE', 0, 0)",
            )
        }
        helper.close()

        val db = Room.databaseBuilder(ctx, KwBooktrackDatabase::class.java, dbName)
            .addMigrations(
                Migrate1To2, Migrate2To3, Migrate3To4, Migrate4To5, Migrate5To6,
                Migrate6To7, Migrate7To8, Migrate8To9, Migrate9To10, Migrate10To11,
                Migrate11To12, Migrate12To13,
            )
            .build()
        try {
            // Первый запрос открывает базу: Room проверяет схему и, если она
            // не сошлась с описанием сущностей, падает здесь.
            assertThat(db.bookDao().count()).isEqualTo(1)
            assertThat(db.bookDao().findById(1)?.title).isEqualTo("Норвежский лес")
            assertThat(db.noteDao().getAll()).hasSize(1)
            assertThat(db.movieDao().count()).isEqualTo(0)
            assertThat(db.mediaDateDao().count()).isEqualTo(0)
        } finally {
            db.close()
        }
    }
}
