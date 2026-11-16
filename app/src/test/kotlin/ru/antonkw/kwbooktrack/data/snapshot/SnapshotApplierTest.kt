package ru.antonkw.kwbooktrack.data.snapshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.CancellingBookDao
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.tagsOf
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Семантика слияния снимка в БД: повторный импорт не плодит дубли,
 * а чужие поля Tellico не затираются.
 */
@RunWith(RobolectricTestRunner::class)
class SnapshotApplierTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var photoRepository: PhotoRepository
    private lateinit var applier: SnapshotApplier
    private lateinit var staging: File
    private lateinit var outputDir: File
    private lateinit var assets: ImportAssets

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, photoRepository)
        noteRepository = NoteRepository(db.noteDao(), NoOpPhotoStorage)
        sessionRepository = SessionRepository(db.readingSessionDao())
        tagRepository = TagRepository(db.tagDao())
        applier = SnapshotApplier(
            bookRepository = bookRepository,
            noteRepository = noteRepository,
            sessionRepository = sessionRepository,
            tagRepository = tagRepository,
            photoRepository = photoRepository,
            photoStorage = NoOpPhotoStorage,
            transcoder = CoverTranscoder(),
        )
        staging = Files.createTempDirectory("kwbooktrack-staging-").toFile()
        outputDir = Files.createTempDirectory("kwbooktrack-out-").toFile()
        assets = ImportAssets(staging, outputDir)
    }

    @After
    fun tearDown() {
        db.close()
        staging.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun applyInsertsBookWithTitleAndStatus() = runTest {
        val stats = applier.apply(snapshot(), assets)

        val books = db.bookDao().observeAll().first()
        assertThat(books).hasSize(1)
        assertThat(books.single().title).isEqualTo("Дорога")
        assertThat(books.single().status).isEqualTo(BookStatus.FINISHED)
        assertThat(stats.books).isEqualTo(1)
    }

    @Test
    fun applyTwiceDoesNotDuplicateNotes() = runTest {
        applier.apply(snapshot(), assets)
        applier.apply(snapshot(), assets)

        val notes = noteRepository.observeByMedia(bookId(), MediaType.BOOKS).first()
        assertThat(notes).hasSize(2)
        assertThat(notes.map { it.bodyMd }).containsExactly("Цитата", "Заметка")
    }

    @Test
    fun applyTwiceDoesNotDuplicateSessions() = runTest {
        applier.apply(snapshot(), assets)
        applier.apply(snapshot(), assets)

        assertThat(sessionRepository.observeByBook(bookId()).first()).hasSize(1)
    }

    @Test
    fun applyTwiceDoesNotDuplicateTags() = runTest {
        applier.apply(snapshot(), assets)
        applier.apply(snapshot(), assets)

        assertThat(tagRepository.observeAll().first()).hasSize(1)
        assertThat(db.tagDao().tagsOf(bookId())).hasSize(1)
    }

    @Test
    fun applyTwiceDoesNotDuplicatePhotos() = runTest {
        archivePhoto(PHOTO_MD5, "jpg")
        val snap = snapshot(photos = listOf(PhotoSnapshot(archivePath = "photos/$PHOTO_MD5.jpg")))

        applier.apply(snap, assets)
        applier.apply(snap, assets)

        val photos = photoRepository.observeByMedia(bookId(), MediaType.BOOKS).first()
        assertThat(photos).hasSize(1)
        assertThat(File(photos.single().path).name).isEqualTo("${PHOTO_MD5}_books_${bookId()}.jpg")
    }

    @Test
    fun photoIsLinkedToNoteBySnapshotIndex() = runTest {
        archivePhoto(PHOTO_MD5, "jpg")
        val snap = snapshot(
            photos = listOf(PhotoSnapshot(archivePath = "photos/$PHOTO_MD5.jpg", noteIndex = 2)),
        )

        applier.apply(snap, assets)

        val notes = noteRepository.observeByMedia(bookId(), MediaType.BOOKS).first().sortedBy { it.id }
        val photo = photoRepository.observeByMedia(bookId(), MediaType.BOOKS).first().single()
        assertThat(photo.noteId).isEqualTo(notes[1].id)
    }

    @Test
    fun extraFieldsAreMergedByKey() = runTest {
        applier.apply(
            snapshot(extraFields = mapOf("чтец" to ExtraField("Чтец", "Иванова"), "переводчик" to ExtraField("Переводчик", "Сидоров"))),
            assets,
        )

        applier.apply(
            snapshot(extraFields = mapOf("чтец" to ExtraField("Чтец", "Петрова"))),
            assets,
        )

        val fields = bookRepository.findById(bookId())!!.extraFields
        // Импорт побеждает по ключу…
        assertThat(fields["чтец"]?.value).isEqualTo("Петрова")
        // …а ключи, которых в импорте нет, сохраняются.
        assertThat(fields["переводчик"]?.value).isEqualTo("Сидоров")
    }

    @Test
    fun coverIsCopiedToOutputDirAndBookPointsToIt() = runTest {
        // Обложка в архиве Tellico лежит во вложенной папке с префиксом
        // коллекции, а снимок знает только имя файла — ищем по имени.
        val cover = File(staging, "tellico/Books_files/abc.webp").apply {
            parentFile?.mkdirs()
            writeBytes(pngBytes())
        }

        applier.apply(snapshot(coverPath = "abc.webp"), assets)

        val path = bookRepository.findById(bookId())!!.coverPath
        // В БД лежит локальный путь, а не имя файла из архива.
        assertThat(path).isNotEqualTo(cover.name)
        assertThat(path).isNotEqualTo("abc.webp")
        val onDisk = File(path!!)
        assertThat(onDisk.exists()).isTrue()
        assertThat(onDisk.parentFile).isEqualTo(File(outputDir, "covers"))
    }

    @Test
    fun missingArchiveFilesAreSkipped() = runTest {
        // Файла обложки и фото в архиве нет — импорт продолжается, книга есть.
        val snap = snapshot(
            coverPath = "abc.webp",
            photos = listOf(PhotoSnapshot(archivePath = "photos/missing.jpg")),
        )

        val stats = applier.apply(snap, assets)

        val book = bookRepository.findById(bookId())!!
        assertThat(book.coverPath).isNull()
        assertThat(photoRepository.observeByMedia(book.id, MediaType.BOOKS).first()).isEmpty()
        assertThat(stats.photos).isEqualTo(0)
        assertThat(stats.covers).isEqualTo(0)
    }

    @Test
    fun snapshotWithoutCoverKeepsExistingOne() = runTest {
        val existingId = db.bookDao().insert(
            BookEntity(
                title = "Дорога",
                author = "К. Иванов",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                coverPath = "/covers/42.webp",
            ),
        )

        applier.apply(snapshot(coverPath = null), assets)

        assertThat(bookRepository.findById(existingId)!!.coverPath).isEqualTo("/covers/42.webp")
    }

    @Test
    fun snapshotBookIdNeverReachesDb() = runTest {
        // Снимок не привязан к строкам БД. Если его id уедет в insert, а такая
        // строка уже занята — вставка молча провалится (OnConflictStrategy.IGNORE),
        // и весь снимок уедет в bookId = -1 (нарушение внешнего ключа).
        db.bookDao().insert(
            BookEntity(
                id = 42L,
                title = "Другая книга",
                author = "Кто-то",
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
        val snap = snapshot().let { it.copy(book = it.book.copy(id = 42L)) }

        applier.apply(snap, assets)

        val imported = db.bookDao().observeAll().first().first { it.title == "Дорога" }
        assertThat(imported.id).isNotEqualTo(42L)
        assertThat(noteRepository.observeByMedia(imported.id, MediaType.BOOKS).first()).hasSize(2)
    }

    @Test
    fun cancellationDuringCoverCopyIsNotSwallowed() = runTest {
        // Отмена импорта не должна выглядеть как «плохая обложка»: общий
        // `catch (e: Exception)` проглатывал бы отмену и импорт выглядел бы
        // успешным.
        val cancelling = BookRepository(
            CancellingBookDao(db.bookDao()),
            db.tagDao(),
            NoOpCoverStorage,
            photoRepository,
        )
        val cancellingApplier = SnapshotApplier(
            bookRepository = cancelling,
            noteRepository = noteRepository,
            sessionRepository = sessionRepository,
            tagRepository = tagRepository,
            photoRepository = photoRepository,
            photoStorage = NoOpPhotoStorage,
            transcoder = CoverTranscoder(),
        )
        File(staging, "abc.webp").writeBytes(pngBytes())

        val error = runCatching {
            cancellingApplier.apply(snapshot(coverPath = "abc.webp"), assets)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    private suspend fun bookId(): Long = db.bookDao().observeAll().first().single().id

    private fun archivePhoto(md5: String, extension: String) {
        File(staging, "photos/$md5.$extension").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
    }

    /** Настоящий PNG — транскодер обложек декодирует по содержимому. */
    private fun pngBytes(w: Int = 8, h: Int = 8): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.RED)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun snapshot(
        coverPath: String? = null,
        extraFields: Map<String, ExtraField> = emptyMap(),
        notes: List<NoteSnapshot> = listOf(
            NoteSnapshot(kind = NoteKind.QUOTE, body = "Цитата", page = 10),
            NoteSnapshot(kind = NoteKind.NOTE, body = "Заметка"),
        ),
        sessions: List<SessionSnapshot> = listOf(
            SessionSnapshot(startedAt = 1_730_000_000_000L, endedAt = 1_730_000_060_000L),
        ),
        tags: List<String> = listOf("фэнтези"),
        photos: List<PhotoSnapshot> = emptyList(),
    ) = BookSnapshot(
        book = BookEntity(
            title = "Дорога",
            author = "К. Иванов",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            status = BookStatus.FINISHED,
            coverPath = coverPath,
        ),
        notes = notes,
        sessions = sessions,
        tags = tags,
        photos = photos,
        extraFields = extraFields,
    )

    private companion object {
        /** MD5 от содержимого фото — имя файла в архиве. */
        private const val PHOTO_MD5 = "5289df737df57326fcdd22597afb1fac"
    }
}
