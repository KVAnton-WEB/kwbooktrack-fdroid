package ru.antonkw.kwbooktrack.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.dao.TagDao

@RunWith(RobolectricTestRunner::class)
class TagRepositoryTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var tagDao: TagDao
    private lateinit var repo: TagRepository

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        tagDao = db.tagDao()
        repo = TagRepository(tagDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun findOrCreateCreatesNewTag() = runTest {
        val id = repo.findOrCreate("  #Fiction ")
        assertThat(id).isGreaterThan(0L)
        assertThat(tagDao.findByName("Fiction")?.id).isEqualTo(id)
    }

    @Test
    fun findOrCreateReturnsExistingId() = runTest {
        val first = repo.findOrCreate("drama")
        val second = repo.findOrCreate("drama")
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun findOrCreateOnBlankReturnsMinusOne() = runTest {
        assertThat(repo.findOrCreate("")).isEqualTo(-1L)
        assertThat(repo.findOrCreate("   ")).isEqualTo(-1L)
        assertThat(repo.findOrCreate("###")).isEqualTo(-1L)
    }

    @Test
    fun attachToBookIdempotentlyLinksTag() = runTest {
        // CrossRef has FK to books.id, so the book row has to exist first.
        db.bookDao().insert(
            ru.antonkw.kwbooktrack.data.db.entity.BookEntity(
                title = "Sample",
                author = "Anon",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        val tagId = repo.findOrCreate("thriller")
        repo.attachToBook(bookId = 1L, tagId = tagId)
        repo.attachToBook(bookId = 1L, tagId = tagId)
        // INSERT OR IGNORE on the cross-ref table — duplicates are no-ops, no
        // crash, no double-row. (Room throws on a real dup PK violation
        // without IGNORE; this asserts the contract.)
        assertThat(tagDao.count()).isEqualTo(1)
    }
}