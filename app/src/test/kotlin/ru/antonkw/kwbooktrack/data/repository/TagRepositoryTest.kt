package ru.antonkw.kwbooktrack.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

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
        repo.attachToMedia(mediaId = 1L, mediaType = MediaType.BOOKS, tagId = tagId)
        repo.attachToMedia(mediaId = 1L, mediaType = MediaType.BOOKS, tagId = tagId)
        // INSERT OR IGNORE on the cross-ref table — duplicates are no-ops, no
        // crash, no double-row. (Room throws on a real dup PK violation
        // without IGNORE; this asserts the contract.)
        assertThat(tagDao.findByName("thriller")).isNotNull()
        assertThat(tagDao.tagsOfMedia(1L, MediaType.BOOKS)).containsExactly("thriller")
    }

    @Test
    fun attachByNameCreatesAndLinks() = runTest {
        db.bookDao().insert(
            ru.antonkw.kwbooktrack.data.db.entity.BookEntity(
                title = "Sample",
                author = "Anon",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        assertThat(repo.attach(1L, MediaType.BOOKS, "  #фэнтези ")).isTrue()
        assertThat(tagDao.tagsOfMedia(1L, MediaType.BOOKS)).containsExactly("фэнтези")
        assertThat(repo.observeAllNames().first()).containsExactly("фэнтези")
    }

    @Test
    fun detachByNameKeepsTagRowForOtherOwners() = runTest {
        db.bookDao().insert(
            ru.antonkw.kwbooktrack.data.db.entity.BookEntity(
                title = "Sample",
                author = "Anon",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        repo.attach(1L, MediaType.BOOKS, "сага")

        assertThat(repo.detach(1L, MediaType.BOOKS, "сага")).isEqualTo(1)
        assertThat(tagDao.tagsOfMedia(1L, MediaType.BOOKS)).isEmpty()
        // Строка тега остаётся: её может использовать другой владелец.
        assertThat(tagDao.findByName("сага")).isNotNull()
    }
}