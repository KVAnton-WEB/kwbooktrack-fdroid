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
 * Migration v13 → v14: индексы по поисковым колонкам уходят.
 *
 * Поиск идёт как `LIKE '%слово%'` — такой LIKE индекс не использует, поэтому
 * индексы по `title_search` и `author_search` были пустой работой на каждой
 * записи.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate13To14Test {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "test-migration-13-to-14.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun migrationDropsSearchIndexesAndKeepsRows() = runBlocking {
        createV13Database()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(13) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.use { db ->
            Migrate13To14.migrate(db)
            val indexes = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'books'").use { cursor ->
                while (cursor.moveToNext()) indexes += cursor.getString(0)
            }
            assertThat(indexes).doesNotContain("index_books_title_search")
            assertThat(indexes).doesNotContain("index_books_author_search")
            // Остальные индексы на месте, строка не потерялась.
            assertThat(indexes).contains("index_books_isbn13")
            db.query("SELECT COUNT(*) FROM books").use { cursor ->
                cursor.moveToFirst()
                assertThat(cursor.getInt(0)).isEqualTo(1)
            }
        }
        helper.close()
    }

    @Test
    fun roomOpensMigratedDatabaseAndKeepsRows() = runBlocking {
        createV13Database()

        val db = Room.databaseBuilder(ctx, KwBooktrackDatabase::class.java, dbName)
            .addMigrations(Migrate13To14)
            .build()
        try {
            // Первый запрос открывает базу: Room проверяет схему и, если не
            // сошлась с описанием сущностей, падает здесь.
            assertThat(db.bookDao().count()).isEqualTo(1)
        } finally {
            db.close()
        }
    }

    /** База версии 13: схема v9 плюс миграции 9 → 10, 10 → 11, 11 → 12 и 12 → 13. */
    private fun createV13Database() {
        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (stmt in TestSchemas.v9) db.execSQL(stmt)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            // Тест открывает v9 напрямую.
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
            Migrate9To10.migrate(db)
            Migrate10To11.migrate(db)
            Migrate11To12.migrate(db)
            Migrate12To13.migrate(db)
            db.version = 13
        }
        helper.close()
    }
}
