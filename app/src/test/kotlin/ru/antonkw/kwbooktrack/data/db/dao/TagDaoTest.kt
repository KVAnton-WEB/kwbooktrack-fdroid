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
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

@RunWith(RobolectricTestRunner::class)
class TagDaoTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var tagDao: TagDao
    private lateinit var bookDao: BookDao
    private var bookId: Long = 0L

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        tagDao = db.tagDao()
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
    fun insertAndFindByName() = runTest {
        val id = tagDao.insert(TagEntity(name = "fiction"))
        assertThat(id).isGreaterThan(0L)
        val found = tagDao.findByName("fiction")
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(id)
    }

    @Test
    fun duplicateNameInsertIsIgnored() = runTest {
        tagDao.insert(TagEntity(name = "fiction"))
        val secondId = tagDao.insert(TagEntity(name = "fiction"))
        assertThat(secondId).isEqualTo(-1L)
    }

    @Test
    fun attachBookTagInsertsCrossRef() = runTest {
        val tagId = tagDao.insert(TagEntity(name = "drama"))
        tagDao.insertCrossRef(MediaTagCrossRef(mediaId = bookId, mediaType = MediaType.BOOKS, tagId = tagId))
        val cleared = tagDao.clearCrossRefsForMedia(bookId, MediaType.BOOKS)
        assertThat(cleared).isEqualTo(1)
    }
}
