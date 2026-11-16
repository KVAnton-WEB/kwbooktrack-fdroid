package ru.antonkw.kwbooktrack.data.db.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

@RunWith(RobolectricTestRunner::class)
class PhotoDaoTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var photoDao: PhotoDao
    private lateinit var bookDao: BookDao
    private var bookId: Long = 0L

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        photoDao = db.photoDao()
        bookDao = db.bookDao()
        bookId = runBlocking {
            bookDao.insert(
                BookEntity(title = "Sample", author = "Anon", createdAt = 0L, updatedAt = 0L),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRoundTrip() = runTest {
        val id = photoDao.insert(
            PhotoEntity(mediaId = bookId, mediaType = MediaType.BOOKS, path = "/tmp/a.jpg", createdAt = 0L),
        )
        assertThat(id).isGreaterThan(0L)
    }

    @Test
    fun deleteForBookRemovesAll() = runTest {
        photoDao.insert(PhotoEntity(mediaId = bookId, mediaType = MediaType.BOOKS, path = "/tmp/a.jpg", createdAt = 0L))
        photoDao.insert(PhotoEntity(mediaId = bookId, mediaType = MediaType.BOOKS, path = "/tmp/b.jpg", createdAt = 0L))
        val deleted = photoDao.deleteForMedia(bookId, MediaType.BOOKS)
        assertThat(deleted).isEqualTo(2)
    }
}
