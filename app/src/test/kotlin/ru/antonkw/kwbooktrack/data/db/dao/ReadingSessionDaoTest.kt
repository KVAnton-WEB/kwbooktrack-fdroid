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
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity

@RunWith(RobolectricTestRunner::class)
class ReadingSessionDaoTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var sessionDao: ReadingSessionDao
    private lateinit var bookDao: BookDao
    private var bookId: Long = 0L

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        sessionDao = db.readingSessionDao()
        bookDao = db.bookDao()
        bookId = runBlocking {
            bookDao.insert(
                BookEntity(
                    title = "Sample",
                    author = "Anon",
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndLatestRoundTrip() = runTest {
        val id = sessionDao.insert(session(startedAt = 100, endPage = 50, durationSec = 1800))
        assertThat(id).isGreaterThan(0L)
        val latest = sessionDao.latestForBook(bookId)
        assertThat(latest).isNotNull()
        assertThat(latest!!.endPage).isEqualTo(50)
    }

    @Test
    fun maxEndPageReturnsHighest() = runTest {
        sessionDao.insert(session(startedAt = 100, endPage = 30))
        sessionDao.insert(session(startedAt = 200, endPage = 80))
        sessionDao.insert(session(startedAt = 300, endPage = 120))
        val max = sessionDao.maxEndPage(bookId)
        assertThat(max).isEqualTo(120)
    }

    @Test
    fun totalDurationSumsAcrossSessions() = runTest {
        sessionDao.insert(session(startedAt = 100, durationSec = 600))
        sessionDao.insert(session(startedAt = 200, durationSec = 1200))
        sessionDao.insert(session(startedAt = 300, durationSec = 1800))
        val total = sessionDao.totalDurationSec(bookId)
        assertThat(total).isEqualTo(3600)
    }

    @Test
    fun deleteForBookCascades() = runTest {
        sessionDao.insert(session(startedAt = 100))
        sessionDao.insert(session(startedAt = 200))
        val deleted = sessionDao.deleteForBook(bookId)
        assertThat(deleted).isEqualTo(2)
    }

    private fun session(
        startedAt: Long,
        endPage: Int? = null,
        durationSec: Long? = null,
    ) = ReadingSessionEntity(
        bookId = bookId,
        startedAt = startedAt,
        endPage = endPage,
        durationSec = durationSec,
    )
}
