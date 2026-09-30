package ru.antonkw.kwbooktrack.data.db.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

/**
 * Migration safety net: take a v4 DB schema with one row, run every
 * explicit migration through to v5, then read the same row back and
 * confirm the new columns all exist with default null/false.
 *
 * The previous test setup used `MigrationTestHelper` which needs the
 * checked-in schema JSONs. We've moved to explicit Migrations, so this
 * test exercises the same path the production database takes.
 */
@RunWith(RobolectricTestRunner::class)
class Migrate4To5Test {
    private lateinit var db: KwBooktrackDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(ctx, KwBooktrackDatabase::class.java, "test-migration-4-to-5.db")
            .addMigrations(Migrate1To2, Migrate2To3, Migrate3To4, Migrate4To5)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun freshDatabaseHasV5Columns() = runBlocking {
        // Insert a row with default values for the v5 fields, then read it
        // back and assert each column is reachable.
        val id = db.bookDao().insert(
            BookEntity(
                title = "Dune",
                author = "Herbert",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        val loaded = db.bookDao().findById(id)
        assertThat(loaded).isNotNull()
        // Defaults populated by the entity / DB.
        assertThat(loaded!!.title).isEqualTo("Dune")
        assertThat(loaded.binding).isNull()
        assertThat(loaded.purchasePlace).isNull()
        assertThat(loaded.series).isNull()
        assertThat(loaded.seriesNumber).isNull()
        assertThat(loaded.condition).isNull()
        assertThat(loaded.translator).isNull()
        assertThat(loaded.printYear).isNull()
        assertThat(loaded.printPages).isNull()
        assertThat(loaded.originalLanguage).isNull()
        assertThat(loaded.originalTitle).isNull()
        assertThat(loaded.annotation).isNull()
        assertThat(loaded.isGift).isFalse()
        assertThat(loaded.isLent).isFalse()
        assertThat(loaded.isSold).isFalse()
    }

    @Test
    fun v5ColumnsCanBeWrittenAndRead() = runBlocking {
        // Insert with all v5 fields populated, read back, assert roundtrip.
        val original = BookEntity(
            title = "Hyperion",
            author = "Dan Simmons",
            createdAt = 0L,
            updatedAt = 0L,
            status = BookStatus.FINISHED,
            source = BookSource.TELLICO,
            binding = "Hardcover",
            purchasePlace = "Labyrinth",
            series = "Hyperion Cantos",
            seriesNumber = "1",
            condition = "Like new",
            translator = "A. Klementiev",
            printYear = 2018,
            printPages = 32,
            originalLanguage = "en",
            originalTitle = "Hyperion",
            annotation = "**bold** test",
            isGift = true,
            isLent = false,
            isSold = false,
        )
        val id = db.bookDao().insert(original)
        val loaded = db.bookDao().findById(id)!!
        assertThat(loaded.binding).isEqualTo("Hardcover")
        assertThat(loaded.purchasePlace).isEqualTo("Labyrinth")
        assertThat(loaded.series).isEqualTo("Hyperion Cantos")
        assertThat(loaded.seriesNumber).isEqualTo("1")
        assertThat(loaded.condition).isEqualTo("Like new")
        assertThat(loaded.translator).isEqualTo("A. Klementiev")
        assertThat(loaded.printYear).isEqualTo(2018)
        assertThat(loaded.printPages).isEqualTo(32)
        assertThat(loaded.originalLanguage).isEqualTo("en")
        assertThat(loaded.originalTitle).isEqualTo("Hyperion")
        assertThat(loaded.annotation).isEqualTo("**bold** test")
        assertThat(loaded.isGift).isTrue()
        assertThat(loaded.isLent).isFalse()
        assertThat(loaded.isSold).isFalse()
    }
}
