package ru.antonkw.kwbooktrack.data.snapshot

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SnapshotBuilderTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var builder: SnapshotBuilder
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        builder = SnapshotBuilder(
            bookRepository = BookRepository(
                db.bookDao(),
                db.tagDao(),
                NoOpCoverStorage,
                PhotoRepository(db.photoDao(), NoOpPhotoStorage),
            ),
            noteDao = db.noteDao(),
            sessionDao = db.readingSessionDao(),
            photoDao = db.photoDao(),
            tagDao = db.tagDao(),
        )
        tempDir = Files.createTempDirectory("kwbooktrack-snapshot-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun buildAllCollectsNotesSessionsTagsAndPhotos() = runTest {
        val bookId = db.bookDao().insert(
            BookEntity(title = "Дорога", author = "К. Иванов", createdAt = 1_000L, updatedAt = 1_000L),
        )
        // Заметки вставляются вперемешку: id не совпадают с порядком
        // (createdAt) и с рангом вида — сортировать должен сборщик.
        db.noteDao().insert(
            NoteEntity(
                bookId = bookId,
                bodyMd = "Поздняя заметка",
                kind = NoteKind.NOTE,
                createdAt = 400L,
                updatedAt = 400L,
            ),
        )
        db.noteDao().insert(
            NoteEntity(
                bookId = bookId,
                bodyMd = "Рецензия",
                kind = NoteKind.REVIEW,
                createdAt = 200L,
                updatedAt = 200L,
            ),
        )
        db.noteDao().insert(
            NoteEntity(
                bookId = bookId,
                bodyMd = "Цитата",
                kind = NoteKind.QUOTE,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )
        val noteId = db.noteDao().insert(
            NoteEntity(
                bookId = bookId,
                bodyMd = "Заметка",
                kind = NoteKind.NOTE,
                createdAt = 300L,
                updatedAt = 300L,
            ),
        )
        db.readingSessionDao().insert(
            ReadingSessionEntity(bookId = bookId, startedAt = 1_730_000_000_000L),
        )
        val tagId = db.tagDao().insert(TagEntity(name = "фэнтези"))
        db.tagDao().insertCrossRef(BookTagCrossRef(bookId = bookId, tagId = tagId))
        val photoFile = File(tempDir, "photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        db.photoDao().insert(
            PhotoEntity(
                bookId = bookId,
                noteId = noteId,
                path = photoFile.absolutePath,
                createdAt = 500L,
            ),
        )

        val snapshots = builder.buildAll()

        assertThat(snapshots).hasSize(1)
        val snapshot = snapshots.single()
        assertThat(snapshot.notes.map { it.kind })
            .containsExactly(NoteKind.QUOTE, NoteKind.REVIEW, NoteKind.NOTE, NoteKind.NOTE).inOrder()
        assertThat(snapshot.notes.map { it.body })
            .containsExactly("Цитата", "Рецензия", "Заметка", "Поздняя заметка").inOrder()
        assertThat(snapshot.tags).containsExactly("фэнтези")
        assertThat(snapshot.sessions).hasSize(1)
        assertThat(snapshot.sessions.single().startedAt).isEqualTo(1_730_000_000_000L)
        assertThat(snapshot.photos).hasSize(1)
        val photo = snapshot.photos.single()
        // Дайджест закреплён константой (сверена через md5sum), а не
        // пересчитывается той же формулой, что и прод-код: иначе ошибка
        // формата (регистр, padding, ширина) прошла бы незамеченной.
        assertThat(photo.archivePath).isEqualTo("photos/$EXPECTED_MD5.jpg")
        val digest = photo.archivePath.removePrefix("photos/").removeSuffix(".jpg")
        assertThat(digest).hasLength(32)
        assertThat(digest.all { it in '0'..'9' || it in 'a'..'f' }).isTrue()
        assertThat(photo.localPath).isEqualTo(photoFile.absolutePath)
        // Фото привязано к заметке NOTE — третья в итоговом списке.
        assertThat(photo.noteIndex).isEqualTo(3)
    }

    @Test
    fun buildAllFallsBackToPhotoIdWhenFileIsMissing() = runTest {
        val bookId = db.bookDao().insert(
            BookEntity(title = "Без фото", author = "Аноним", createdAt = 1L, updatedAt = 1L),
        )
        val photoId = db.photoDao().insert(
            PhotoEntity(
                bookId = bookId,
                path = File(tempDir, "missing.jpg").absolutePath,
                createdAt = 1L,
            ),
        )

        val photo = builder.buildAll().single().photos.single()

        assertThat(photo.archivePath).isEqualTo("photos/$photoId.jpg")
        assertThat(photo.noteIndex).isNull()
    }

    companion object {
        private val PHOTO_BYTES = byteArrayOf(1, 2, 3)

        /** MD5 от [PHOTO_BYTES], сверенный через `md5sum`. */
        private const val EXPECTED_MD5 = "5289df737df57326fcdd22597afb1fac"
    }
}
