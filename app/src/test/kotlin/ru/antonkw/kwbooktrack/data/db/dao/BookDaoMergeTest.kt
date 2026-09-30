package ru.antonkw.kwbooktrack.data.db.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

/**
 * BookDao is the only place that mutates the books table on import. Tests
 * here pin the merge semantics so a future refactor can't silently wipe
 * user-set fields when an importer reruns.
 */
@RunWith(RobolectricTestRunner::class)
class BookDaoMergeTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.bookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun v5NullableFieldsArePreservedWhenIncomingIsNull() = runBlocking {
        // User sets binding manually, then re-imports a Tellico row that
        // doesn't have a Переплёт cell. Without the merge fix, binding would
        // get nulled out.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Твёрдый",
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = null, // empty CSV cell
            ),
        )

        val row = dao.observeAll().first()
        assertThat(row).isNotEmpty()
        assertThat(row.first().binding).isEqualTo("Твёрдый")
    }

    @Test
    fun v5BooleansAreORedAcrossImports() = runBlocking {
        // User marked the book as a gift. A re-import that doesn't know
        // about it (Bookmory row, blank Tellico cell) must not silently
        // flip isGift back to false.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                isGift = true,
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                isGift = false, // empty CSV cell
            ),
        )

        assertThat(dao.observeAll().first().first().isGift).isTrue()
    }

    @Test
    fun v5IncomingValueWinsWhenPresent() = runBlocking {
        // When the import DOES supply a value, that value wins (overwrites
        // the existing one). E.g. user had "Твёрдый", new Tellico export
        // says "Мягкий" — the new value is authoritative.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Твёрдый",
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Мягкий",
            ),
        )

        assertThat(dao.observeAll().first().first().binding).isEqualTo("Мягкий")
    }

    @Test
    fun sourceIsPreservedAcrossImport() = runBlocking {
        // Originally imported via Bookmory; a later Tellico import must
        // not flip source back to TELLICO.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                source = BookSource.BOOKMORY,
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                source = BookSource.TELLICO,
            ),
        )

        assertThat(dao.observeAll().first().first().source).isEqualTo(BookSource.BOOKMORY)
    }

    @Test
    fun statusUpgradeFromWishlistKeepsExistingFinished() = runBlocking {
        // User marks the book as FINISHED. A re-import with no status info
        // (defaults to WISHLIST in the mapper) must not downgrade it.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                status = BookStatus.FINISHED,
                finishedAt = 1_700_000_000_000L,
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                status = BookStatus.WISHLIST,
            ),
        )

        val row = dao.observeAll().first().first()
        assertThat(row.status).isEqualTo(BookStatus.FINISHED)
        assertThat(row.finishedAt).isEqualTo(1_700_000_000_000L)
    }
}

