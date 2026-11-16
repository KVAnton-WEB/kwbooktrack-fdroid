package ru.antonkw.kwbooktrack.data.db.dao

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
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity

/**
 * Удаление содержимого записи — заметок, фото и связей тегов.
 *
 * Внешнего ключа на `books` у них больше нет: владельцем может быть и фильм,
 * а фильма в `books` нет. Поэтому чистка делается вручную, в DAO, и самое
 * важное в ней — не задеть чужое.
 *
 * id книг и id фильмов считаются независимо и **совпадают**: книга 1 и фильм 1
 * живут рядом, и удаление одного не должно тронуть содержимое другого.
 */
@RunWith(RobolectricTestRunner::class)
class MediaContentDeletionTest {

    private lateinit var db: KwBooktrackDatabase

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deletingMovieKeepsBookContent() = runTest {
        val bookId = db.bookDao().insert(book())
        val movieId = db.movieDao().insert(movie())
        seedContent(bookId, MediaType.BOOKS, "книга")
        seedContent(movieId, MediaType.MOVIES, "фильм")

        db.movieDao().deleteWithContent(movieId)

        assertThat(notesOf(bookId, MediaType.BOOKS)).hasSize(1)
        assertThat(notesOf(movieId, MediaType.MOVIES)).isEmpty()
        assertThat(photosOf(bookId, MediaType.BOOKS)).hasSize(1)
        assertThat(photosOf(movieId, MediaType.MOVIES)).isEmpty()
        assertThat(tagsOf(bookId, MediaType.BOOKS)).hasSize(1)
        assertThat(tagsOf(movieId, MediaType.MOVIES)).isEmpty()
        assertThat(db.bookDao().findById(bookId)).isNotNull()
        assertThat(db.movieDao().findById(movieId)).isNull()
    }

    @Test
    fun deletingBookKeepsMovieContent() = runTest {
        val bookId = db.bookDao().insert(book())
        val movieId = db.movieDao().insert(movie())
        seedContent(bookId, MediaType.BOOKS, "книга")
        seedContent(movieId, MediaType.MOVIES, "фильм")

        db.bookDao().deleteWithContent(bookId)

        assertThat(notesOf(bookId, MediaType.BOOKS)).isEmpty()
        assertThat(notesOf(movieId, MediaType.MOVIES)).hasSize(1)
        assertThat(photosOf(bookId, MediaType.BOOKS)).isEmpty()
        assertThat(photosOf(movieId, MediaType.MOVIES)).hasSize(1)
        assertThat(tagsOf(bookId, MediaType.BOOKS)).isEmpty()
        assertThat(tagsOf(movieId, MediaType.MOVIES)).hasSize(1)
        assertThat(db.bookDao().findById(bookId)).isNull()
        assertThat(db.movieDao().findById(movieId)).isNotNull()
    }

    private fun book() = BookEntity(
        title = "Дюна",
        author = "Фрэнк Герберт",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun movie() = MovieEntity(
        title = "Малхолланд Драйв",
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** Заметка, фото и тег владельцу — по одному каждого. */
    private suspend fun seedContent(mediaId: Long, mediaType: MediaType, marker: String) {
        val noteId = db.noteDao().insert(
            NoteEntity(
                mediaId = mediaId,
                mediaType = mediaType,
                bodyMd = "заметка $marker",
                kind = NoteKind.NOTE,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        db.photoDao().insert(
            PhotoEntity(
                mediaId = mediaId,
                mediaType = mediaType,
                noteId = noteId,
                path = "/files/photos/$marker.jpg",
                createdAt = 1L,
            ),
        )
        val tagId = db.tagDao().insert(TagEntity(name = "тег $marker"))
        db.tagDao().insertCrossRef(
            MediaTagCrossRef(mediaId = mediaId, mediaType = mediaType, tagId = tagId),
        )
    }

    private suspend fun notesOf(mediaId: Long, mediaType: MediaType) =
        db.noteDao().getAll().filter { it.mediaId == mediaId && it.mediaType == mediaType }

    private suspend fun photosOf(mediaId: Long, mediaType: MediaType) =
        db.photoDao().getAll().filter { it.mediaId == mediaId && it.mediaType == mediaType }

    /** Теги владельца — по паре (id, тип): id у книг и фильмов совпадают. */
    private suspend fun tagsOf(mediaId: Long, mediaType: MediaType) =
        db.tagDao().tagsForAllMedia().filter { it.mediaId == mediaId && it.mediaType == mediaType }
}
