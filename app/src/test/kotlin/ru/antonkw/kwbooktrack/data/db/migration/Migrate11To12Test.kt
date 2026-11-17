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
 * Migration v11 → v12: появляется таблица дополнительных дат просмотра и
 * прочтения.
 *
 * Данных переносить нечего: основные даты остаются в своих колонках, а
 * дополнительных ещё нет. Таблица ни на что не ссылается, поэтому порядок
 * пересоздания таблиц (как в 9 → 10) здесь не нужен.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate11To12Test {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "test-migration-11-to-12.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun roomOpensMigratedDatabaseAndKeepsRows() = runBlocking {
        createV11Database()

        val db = Room.databaseBuilder(ctx, KwBooktrackDatabase::class.java, dbName)
            .addMigrations(Migrate9To10, Migrate10To11, Migrate11To12, Migrate12To13)
            .build()
        try {
            // Первый запрос открывает базу: Room проверяет схему и, если не
            // сошлась с описанием сущностей, падает здесь.
            assertThat(db.bookDao().count()).isEqualTo(1)
            assertThat(db.movieDao().count()).isEqualTo(0)
            // Таблица дат появилась и пока пуста.
            assertThat(db.mediaDateDao().count()).isEqualTo(0)
        } finally {
            db.close()
        }
    }

    /** База версии 11: схема v9 плюс миграции 9 → 10 и 10 → 11. */
    private fun createV11Database() {
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
            db.version = 11
        }
        helper.close()
    }
}
