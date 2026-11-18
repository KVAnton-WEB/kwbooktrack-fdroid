package ru.antonkw.kwbooktrack.data.db.dao

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
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

@RunWith(RobolectricTestRunner::class)
class BookDaoTest {
    private lateinit var db: ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
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
    fun insertAndReadRoundTrip() = runTest {
        val now = 1_000_000L
        val id = dao.insert(sampleBook(title = "Dune", author = "Frank Herbert", createdAt = now, updatedAt = now))
        assertThat(id).isGreaterThan(0L)

        val found = dao.findById(id)
        assertThat(found).isNotNull()
        assertThat(found!!.title).isEqualTo("Dune")
        assertThat(found.author).isEqualTo("Frank Herbert")
        assertThat(found.status).isEqualTo(BookStatus.WISHLIST)
        assertThat(found.source).isEqualTo(MediaSource.MANUAL)
    }

    @Test
    fun findByIsbnReturnsExistingBook() = runTest {
        val book = sampleBook(isbn13 = "9780441172719", createdAt = 0L, updatedAt = 0L)
        val id = dao.insert(book)
        val found = dao.findByIsbn("9780441172719")
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(id)
    }

    @Test
    fun findByIsbnReturnsNullWhenAbsent() = runTest {
        val found = dao.findByIsbn("9999999999999")
        assertThat(found).isNull()
    }

    @Test
    fun insertWithDuplicateIsbnIsIgnored() = runTest {
        val first = sampleBook(isbn13 = "9780441172719", createdAt = 0L, updatedAt = 0L)
        val firstId = dao.insert(first)
        val second = sampleBook(title = "Other", isbn13 = "9780441172719", createdAt = 0L, updatedAt = 0L)
        val secondId = dao.insert(second)
        assertThat(secondId).isEqualTo(-1L)
        assertThat(dao.count()).isEqualTo(1)
        assertThat(firstId).isGreaterThan(0L)
    }

    @Test
    fun updateModifiesFields() = runTest {
        val id = dao.insert(sampleBook(createdAt = 0L, updatedAt = 0L))
        val existing = dao.findById(id)!!
        val updated = existing.copy(rating = 5, status = BookStatus.FINISHED)
        dao.update(updated)
        val reloaded = dao.findById(id)!!
        assertThat(reloaded.rating).isEqualTo(5)
        assertThat(reloaded.status).isEqualTo(BookStatus.FINISHED)
    }

    @Test
    fun deleteRemovesBook() = runTest {
        val id = dao.insert(sampleBook(createdAt = 0L, updatedAt = 0L))
        dao.deleteById(id)
        assertThat(dao.findById(id)).isNull()
    }

    @Test
    fun observeAllEmitsInserts() = runTest {
        dao.insert(sampleBook(title = "A", createdAt = 0L, updatedAt = 0L))
        dao.insert(sampleBook(title = "B", createdAt = 0L, updatedAt = 1L))
        val items = dao.observeAll().first()
        assertThat(items).hasSize(2)
    }

    @Test
    fun countReflectsInserts() = runTest {
        assertThat(dao.count()).isEqualTo(0)
        dao.insert(sampleBook(title = "A", createdAt = 0L, updatedAt = 0L))
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun observeByIdReturnsTheBookOrNull() = runTest {
        val id = dao.insert(sampleBook(title = "Дюна", createdAt = 0L, updatedAt = 0L))

        assertThat(dao.observeById(id).first()?.title).isEqualTo("Дюна")
        assertThat(dao.observeById(id + 1000).first()).isNull()
    }

    private fun sampleBook(
        title: String = "Sample",
        author: String = "Anon",
        isbn13: String? = null,
        createdAt: Long,
        updatedAt: Long,
    ) = BookEntity(
        title = title,
        author = author,
        isbn13 = isbn13,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
