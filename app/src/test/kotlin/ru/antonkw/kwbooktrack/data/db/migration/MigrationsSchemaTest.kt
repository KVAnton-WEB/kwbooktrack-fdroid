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
    private val v9Schema = TestSchemas.v9
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
            .addMigrations(Migrate9To10, Migrate10To11, Migrate11To12, Migrate12To13)
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
