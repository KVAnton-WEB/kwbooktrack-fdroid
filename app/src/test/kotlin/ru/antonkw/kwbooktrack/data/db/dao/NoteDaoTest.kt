package ru.antonkw.kwbooktrack.data.db.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

@RunWith(RobolectricTestRunner::class)
class NoteDaoTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var bookDao: BookDao
    private var bookId: Long = 0L

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        noteDao = db.noteDao()
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
    fun insertAndFindRoundTrip() = runTest {
        val id = noteDao.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
                bodyMd = "thought",
                kind = NoteKind.NOTE,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        assertThat(id).isGreaterThan(0L)
        val found = noteDao.findById(id)
        assertThat(found).isNotNull()
        assertThat(found!!.bodyMd).isEqualTo("thought")
    }

    @Test
    fun insertRespectsKind() = runTest {
        noteDao.insert(
            NoteEntity(mediaId = bookId, mediaType = MediaType.BOOKS, bodyMd = "a", kind = NoteKind.QUOTE, createdAt = 0L, updatedAt = 0L),
        )
        noteDao.insert(
            NoteEntity(mediaId = bookId, mediaType = MediaType.BOOKS, bodyMd = "b", kind = NoteKind.REVIEW, createdAt = 0L, updatedAt = 0L),
        )
        val quotes = noteDao.observeByKind(NoteKind.QUOTE).first()
        val reviews = noteDao.observeByKind(NoteKind.REVIEW).first()
        assertThat(quotes).hasSize(1)
        assertThat(reviews).hasSize(1)
    }

    @Test
    fun deleteRemovesNote() = runTest {
        val id = noteDao.insert(
            NoteEntity(mediaId = bookId, mediaType = MediaType.BOOKS, bodyMd = "x", createdAt = 0L, updatedAt = 0L),
        )
        val note = noteDao.findById(id)!!
        noteDao.delete(note)
        assertThat(noteDao.findById(id)).isNull()
    }
}
