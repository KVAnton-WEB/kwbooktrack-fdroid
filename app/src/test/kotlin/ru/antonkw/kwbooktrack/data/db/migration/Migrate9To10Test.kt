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
 * Exercises [Migrate9To10] against a hand-crafted v9 schema. Same approach as
 * [Migrate8To9Test]: open a raw SupportSQLiteOpenHelper at v9, drive the
 * migration by hand, then read back.
 *
 * Миграция переводит заметки, фото и связи тегов из «принадлежит книге»
 * (`book_id` + внешний ключ на `books`) в «принадлежит книге или фильму»
 * (`media_id` + `media_type`). Внешнего ключа на `books` у них больше нет —
 * заметка может принадлежать фильму, а фильма в `books` нет.
 *
 * Поэтому таблицы пересоздаются, а `photos` перед этим уходит в страховку:
 * у него есть внешний ключ на `notes` с `ON DELETE SET NULL`, и `DROP TABLE
 * notes` обнулил бы `photos.note_id` (см. комментарий в [Migrate7To8]).
 */
@RunWith(RobolectricTestRunner::class)
class Migrate9To10Test {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration-9-to-10.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    private val v9Schema = TestSchemas.v9
    private fun openV9Helper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            // Без этого каскад не сработал бы и тест ничего не проверял бы.
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in v9Schema) db.execSQL(stmt)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // No-op: test opens v9 directly.
                        }
                    },
                )
                .build(),
        )

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO books (id, title, author, status, language, source, created_at, updated_at, " +
                "title_search, author_search, series_search, print_pages, extra_fields) " +
                "VALUES (1, 'Норвежский лес', 'Мураками', 'FINISHED', 'ru', 'MANUAL', 0, 0, '', '', '', 32.5, '{}')",
        )
        db.execSQL(
            "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                "VALUES (10, 1, 'мысль', 'NOTE', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO notes (id, book_id, body_md, kind, created_at, updated_at) " +
                "VALUES (11, 1, 'цитата', 'QUOTE', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO photos (id, book_id, note_id, path, created_at) " +
                "VALUES (20, 1, 10, '/files/photos/p.jpg', 0)",
        )
        db.execSQL("INSERT INTO tags (id, name) VALUES (5, 'классика')")
        db.execSQL("INSERT INTO book_tags (book_id, tag_id) VALUES (1, 5)")
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    /** Имена колонок таблицы — по `PRAGMA table_info`. */
    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(1)
            names
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { c ->
            c.moveToFirst()
        }

    @Test
    fun notesColumnsBecomeMediaScoped() {
        val helper = openV9Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate9To10.migrate(db)

            assertThat(columns(db, "notes")).containsExactly("id", "media_id", "media_type", "page", "body_md", "kind", "created_at", "updated_at")
            assertThat(columns(db, "notes")).doesNotContain("book_id")
        } finally {
            helper.close()
        }
    }

    @Test
    fun photosColumnsBecomeMediaScoped() {
        val helper = openV9Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate9To10.migrate(db)

            assertThat(columns(db, "photos")).containsExactly("id", "media_id", "media_type", "note_id", "path", "created_at")
            assertThat(columns(db, "photos")).doesNotContain("book_id")
        } finally {
            helper.close()
        }
    }

    @Test
    fun bookTagsTableBecomesMediaTags() {
        val helper = openV9Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate9To10.migrate(db)

            assertThat(tableExists(db, "media_tags")).isTrue()
            assertThat(tableExists(db, "book_tags")).isFalse()
            assertThat(columns(db, "media_tags")).containsExactly("media_id", "media_type", "tag_id")
        } finally {
            helper.close()
        }
    }

    @Test
    fun existingRowsSurviveTheMigration() {
        val helper = openV9Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate9To10.migrate(db)

            assertThat(count(db, "books")).isEqualTo(1)
            assertThat(count(db, "notes")).isEqualTo(2)
            assertThat(count(db, "photos")).isEqualTo(1)
            assertThat(count(db, "tags")).isEqualTo(1)
            assertThat(count(db, "media_tags")).isEqualTo(1)

            db.query("SELECT media_id, media_type, body_md FROM notes WHERE id = 10").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(1L)
                assertThat(c.getString(1)).isEqualTo("BOOKS")
                assertThat(c.getString(2)).isEqualTo("мысль")
            }
            db.query("SELECT media_id, media_type FROM media_tags").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(1L)
                assertThat(c.getString(1)).isEqualTo("BOOKS")
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun photoKeepsLinkToItsNote() {
        // Регрессия: `photos.note_id` ссылается на `notes` с ON DELETE SET NULL,
        // поэтому при пересоздании `notes` фото должно уйти в страховку —
        // иначе миграция обнулила бы привязку фото к заметке.
        val helper = openV9Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate9To10.migrate(db)

            db.query("SELECT note_id, path FROM photos WHERE id = 20").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(10)
                assertThat(c.getString(1)).isEqualTo("/files/photos/p.jpg")
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun booksTableIsNotTouched() {
        val helper = openV9Helper()
        try {
            val db = helper.writableDatabase
            seed(db)

            Migrate9To10.migrate(db)

            assertThat(columns(db, "books")).containsAtLeast("id", "title", "author", "extra_fields")
            db.query("SELECT title, print_pages FROM books WHERE id = 1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("Норвежский лес")
                assertThat(c.getDouble(1)).isEqualTo(32.5)
            }
        } finally {
            helper.close()
        }
    }
}
