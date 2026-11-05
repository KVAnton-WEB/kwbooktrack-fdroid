package ru.antonkw.kwbooktrack.data.importer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.CancellingBookDao
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.exporter.ExportZipUseCase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Импорт своего zip-бэкапа: книги со всеми полями, заметки, сессии, теги,
 * фото и чужие поля — всё из архива, без дублей при повторном импорте.
 */
@RunWith(RobolectricTestRunner::class)
class ImportKwBooktrackZipUseCaseTest {
    private lateinit var sourceDb: KwBooktrackDatabase
    private lateinit var sourceRepo: BookRepository
    private lateinit var export: ExportZipUseCase

    private lateinit var targetDb: KwBooktrackDatabase
    private lateinit var targetRepo: BookRepository
    private lateinit var targetNoteRepo: NoteRepository
    private lateinit var targetSessionRepo: SessionRepository
    private lateinit var targetTagRepo: TagRepository
    private lateinit var targetPhotoRepo: PhotoRepository
    private lateinit var importer: ImportKwBooktrackZipUseCase

    private lateinit var tempDir: File
    private lateinit var outputDir: File

    private val versionProvider = object : AppVersionProvider {
        override val appVersion: String = "0.1.0-test"
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("kwbooktrack-roundtrip-").toFile()
        outputDir = Files.createTempDirectory("kwbooktrack-out-").toFile()

        sourceDb = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        sourceRepo = BookRepository(
            bookDao = sourceDb.bookDao(),
            tagDao = sourceDb.tagDao(),
            coverStorage = NoOpCoverStorage,
            photoRepository = PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage),
        )
        export = ExportZipUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = sourceRepo,
                noteDao = sourceDb.noteDao(),
                sessionDao = sourceDb.readingSessionDao(),
                photoDao = sourceDb.photoDao(),
                tagDao = sourceDb.tagDao(),
            ),
            versionProvider = versionProvider,
            database = sourceDb,
        )

        targetDb = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        targetPhotoRepo = PhotoRepository(targetDb.photoDao(), NoOpPhotoStorage)
        targetRepo = BookRepository(
            bookDao = targetDb.bookDao(),
            tagDao = targetDb.tagDao(),
            coverStorage = NoOpCoverStorage,
            photoRepository = targetPhotoRepo,
        )
        targetNoteRepo = NoteRepository(
            noteDao = targetDb.noteDao(),
            photoStorage = NoOpPhotoStorage,
        )
        targetSessionRepo = SessionRepository(targetDb.readingSessionDao())
        targetTagRepo = TagRepository(targetDb.tagDao())
        importer = ImportKwBooktrackZipUseCase(
            SnapshotApplier(
                bookRepository = targetRepo,
                noteRepository = targetNoteRepo,
                sessionRepository = targetSessionRepo,
                tagRepository = targetTagRepo,
                photoRepository = targetPhotoRepo,
                photoStorage = NoOpPhotoStorage,
                transcoder = CoverTranscoder(),
            ),
        )
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        tempDir.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun exportThenImport_roundTripsBooks() {
        runBlocking {
            sourceRepo.insert(
                BookEntity(
                    title = "Мастер и Маргарита",
                    author = "Булгаков",
                    pageCount = 480,
                    status = BookStatus.FINISHED,
                    language = "ru",
                    rating = 5,
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                ),
            )
            sourceRepo.insert(
                BookEntity(
                    title = "Солярис",
                    author = "Лем",
                    pageCount = 320,
                    status = BookStatus.READING,
                    language = "ru",
                    createdAt = 1_700_000_001_000L,
                    updatedAt = 1_700_000_001_000L,
                ),
            )

            val outFile = File(tempDir, "export.zip")
            val manifest = export(outFile)

            // Counts в манифесте реальные (issue #3).
            assertThat(manifest.counts.books).isEqualTo(2)

            val result = importer(outFile, outputDir)
            assertThat(result.isSuccess).isTrue()
            assertThat(result.imported).isEqualTo(2)

            val restored = targetRepo.observeAll().first()
            assertThat(restored).hasSize(2)
            val titles = restored.map { it.title }.toSet()
            assertThat(titles).containsExactly("Мастер и Маргарита", "Солярис")
        }
    }

    @Test
    fun import_rejectsForeignManifest() {
        runBlocking {
            // Создаём ZIP с чужим source.
            val fake = File(tempDir, "fake.zip")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(fake)).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write(
                    """{"schemaVersion":1,"appVersion":"x","exportedAt":0,"source":"other","counts":{"books":0,"sessions":0,"notes":0,"quotes":0,"photos":0,"tags":0}}"""
                        .toByteArray(),
                )
                zip.closeEntry()
            }
            val result = importer(fake, outputDir)
            assertThat(result.isSuccess).isFalse()
            assertThat(result.error?.message).contains("kwbooktrack")
        }
    }

    @Test
    fun import_withoutManifestFails() {
        runBlocking {
            val fake = File(tempDir, "no-manifest.zip")
            ZipOutputStream(fake.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("books.csv"))
                zip.write("title;author\n".toByteArray())
                zip.closeEntry()
            }
            val result = importer(fake, outputDir)
            assertThat(result.isSuccess).isFalse()
            assertThat(result.error?.message).contains("manifest.json")
        }
    }

    @Test
    fun import_emptyCsvReportsZero() {
        runBlocking {
            val outFile = File(tempDir, "empty.zip")
            export(outFile)
            val result = importer(outFile, outputDir)
            assertThat(result.isSuccess).isTrue()
            assertThat(result.imported).isEqualTo(0)
        }
    }

    @Test
    fun import_readsExtraJsonAndAllBookFields() {
        runBlocking {
            val zip = backupZip()

            val result = importer(zip, outputDir)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.total).isEqualTo(1)
            assertThat(result.imported).isEqualTo(1)

            val book = targetRepo.observeAll().first().single()
            assertThat(book.title).isEqualTo("Мастер и Маргарита")
            assertThat(book.author).isEqualTo("Булгаков")
            assertThat(book.status).isEqualTo(BookStatus.FINISHED)
            assertThat(book.pageCount).isEqualTo(480)
            assertThat(book.binding).isEqualTo("твёрдый")
            assertThat(book.purchasePlace).isEqualTo("Книжный поезд")
            assertThat(book.series).isEqualTo("Классика")
            assertThat(book.printPages).isEqualTo(32.5)
            assertThat(book.purchasePrice).isEqualTo(350.5)
            assertThat(book.isGift).isTrue()
            // Чужие поля — из extra.json.
            assertThat(book.extraFields["посоветовал"]?.value).isEqualTo("Книжный поезд")

            val notes = targetNoteRepo.observeByBook(book.id).first()
            assertThat(notes.map { it.kind }).containsExactly(NoteKind.QUOTE, NoteKind.NOTE)
            assertThat(notes.first { it.kind == NoteKind.QUOTE }.page).isEqualTo(12)
            assertThat(notes.first { it.kind == NoteKind.QUOTE }.createdAt).isEqualTo(1_730_000_000_000L)

            val sessions = targetSessionRepo.observeByBook(book.id).first()
            assertThat(sessions).hasSize(1)
            assertThat(sessions.single().durationSec).isEqualTo(600)

            assertThat(targetTagRepo.observeAll().first().map { it.name }).containsExactly("фэнтези")

            val photos = targetPhotoRepo.observeByBook(book.id).first()
            assertThat(photos).hasSize(1)
            // noteIndex — 1-based индекс заметки в снимке: вторая заметка.
            assertThat(photos.single().noteId).isEqualTo(notes.first { it.kind == NoteKind.NOTE }.id)

            // Обложка из архива легла в каталог обложек, в БД — локальный путь.
            val cover = File(book.coverPath!!)
            assertThat(cover.exists()).isTrue()
            assertThat(cover.parentFile).isEqualTo(File(outputDir, "covers"))
        }
    }

    @Test
    fun import_oldBackupWithoutIdColumnSkipsExtraJson() {
        runBlocking {
            // Старый бэкап: колонки `id` нет — `extra.json` привязать не к чему,
            // его данные игнорируются, но импорт не падает.
            val zip = File(tempDir, "old.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(MANIFEST.toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("books.csv"))
                out.write("title;author\nСтарый бэкап;Автор\n".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("extra.json"))
                out.write(EXTRA_JSON.toByteArray())
                out.closeEntry()
            }

            val result = importer(zip, outputDir)

            assertThat(result.isSuccess).isTrue()
            assertThat(result.imported).isEqualTo(1)
            val book = targetRepo.observeAll().first().single()
            assertThat(book.title).isEqualTo("Старый бэкап")
            assertThat(targetNoteRepo.observeByBook(book.id).first()).isEmpty()
            // В старом бэкапе колонок меток времени нет — откат на «сейчас».
            assertThat(book.createdAt).isGreaterThan(1_000_000_000_000L)
            assertThat(book.updatedAt).isGreaterThan(1_000_000_000_000L)
        }
    }

    @Test
    fun import_readsBookTimestampsFromCsv() {
        runBlocking {
            val zip = File(tempDir, "with-time.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(MANIFEST.toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("books.csv"))
                out.write(
                    ("title;author;created_at;updated_at\n" +
                        "С книгой времени;Автор;1700000000000;1700000009999\n").toByteArray(),
                )
                out.closeEntry()
            }

            val result = importer(zip, outputDir)

            assertThat(result.isSuccess).isTrue()
            val book = targetRepo.observeAll().first().single()
            assertThat(book.createdAt).isEqualTo(1_700_000_000_000L)
            assertThat(book.updatedAt).isEqualTo(1_700_000_009_999L)
        }
    }

    @Test
    fun importTwice_doesNotDuplicateAnything() {        runBlocking {
            val zip = backupZip()

            importer(zip, outputDir)
            importer(zip, outputDir)

            val book = targetRepo.observeAll().first().single()
            assertThat(targetRepo.observeAll().first()).hasSize(1)
            assertThat(targetNoteRepo.observeByBook(book.id).first()).hasSize(2)
            assertThat(targetSessionRepo.observeByBook(book.id).first()).hasSize(1)
            assertThat(targetPhotoRepo.observeByBook(book.id).first()).hasSize(1)
            assertThat(targetDb.tagDao().tagsForBook(book.id)).hasSize(1)
        }
    }

    @Test
    fun cancellationIsNotSwallowedAsSkippedRow() {
        runBlocking {
            // Отменённый импорт должен отмениться, а не «пропустить» книгу и
            // отчитаться успехом.
            val photoRepository = PhotoRepository(targetDb.photoDao(), NoOpPhotoStorage)
            val cancelling = ImportKwBooktrackZipUseCase(
                SnapshotApplier(
                    bookRepository = BookRepository(
                        CancellingBookDao(targetDb.bookDao()),
                        targetDb.tagDao(),
                        NoOpCoverStorage,
                        photoRepository,
                    ),
                    noteRepository = NoteRepository(targetDb.noteDao(), NoOpPhotoStorage),
                    sessionRepository = SessionRepository(targetDb.readingSessionDao()),
                    tagRepository = TagRepository(targetDb.tagDao()),
                    photoRepository = photoRepository,
                    photoStorage = NoOpPhotoStorage,
                    transcoder = CoverTranscoder(),
                ),
            )

            val error = runCatching { cancelling(backupZip(), outputDir) }.exceptionOrNull()

            assertThat(error).isInstanceOf(CancellationException::class.java)
        }
    }

    /** Бэкап со всеми частями: книга, `extra.json`, обложка и фото. */
    private fun backupZip(): File {
        val zip = File(tempDir, "backup.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(MANIFEST.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("books.csv"))
            out.write(BOOKS_CSV.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("extra.json"))
            out.write(EXTRA_JSON.toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("covers/620.jpg"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
            out.putNextEntry(ZipEntry("photos/abc.jpg"))
            out.write(byteArrayOf(4, 5, 6))
            out.closeEntry()
        }
        return zip
    }

    private companion object {
        private val MANIFEST = """
            {"schemaVersion":1,"appVersion":"0.5.45","exportedAt":1730000000000,"source":"kwbooktrack",
             "counts":{"books":1,"sessions":1,"notes":2,"quotes":1,"photos":1,"tags":1}}
        """.trimIndent()

        private val BOOKS_CSV = (
            "title;subtitle;author;isbn13;page_count;language;genre;publisher;year;cover_path;rating;status;" +
                "started_at;finished_at;binding;purchase_place;series;series_number;condition;translator;print_year;" +
                "print_pages;original_language;original_title;annotation;is_gift;is_lent;is_sold;" +
                "purchase_date;purchase_price;source;id\n" +
                "Мастер и Маргарита;Фантастический роман;Булгаков;9785010011963;480;ru;роман;Азбука;1967;" +
                "/data/user/0/app/files/covers/620.jpg;5;FINISHED;2024-01-01;2024-02-01;твёрдый;Книжный поезд;" +
                "Классика;1;новое;Берг;2000;32,5;ru;The Master and Margarita;Аннотация;1;;0;" +
                "2024-01-02;350,50;MANUAL;620\n"
            )

        private val EXTRA_JSON = """
            {"books":{"620":{
              "notes":[
                {"kind":"QUOTE","page":12,"body":"Цитата","createdAt":1730000000000},
                {"kind":"NOTE","page":null,"body":"Заметка","createdAt":1730000001000}],
              "sessions":[{"startedAt":1730000000000,"endedAt":1730000600000,"startPage":1,"endPage":20,
                           "durationSec":600,"note":"чтение"}],
              "tags":["фэнтези"],
              "photos":[{"file":"photos/abc.jpg","noteIndex":2,"createdAt":1730000002000}],
              "extraFields":{"посоветовал":{"t":"Посоветовал","v":"Книжный поезд"}}}}}
        """.trimIndent()
    }
}
