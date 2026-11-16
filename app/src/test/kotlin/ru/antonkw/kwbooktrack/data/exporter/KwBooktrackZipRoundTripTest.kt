package ru.antonkw.kwbooktrack.data.exporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.tagsOf
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.importer.ImportKwBooktrackZipUseCase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.storage.FilePhotoStorage
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Свой zip-бэкап без потерь: `books.csv` + `extra.json` + `photos/` + обложки —
 * и обратный импорт: всё на месте, ничего не теряется.
 */
@RunWith(RobolectricTestRunner::class)
class KwBooktrackZipRoundTripTest {
    private lateinit var sourceDb: KwBooktrackDatabase
    private lateinit var targetDb: KwBooktrackDatabase
    private lateinit var exportUseCase: ExportZipUseCase
    private lateinit var importUseCase: ImportKwBooktrackZipUseCase
    private lateinit var sourceBooks: BookRepository
    private lateinit var sourceNotes: NoteRepository
    private lateinit var sourceSessions: SessionRepository
    private lateinit var sourceTags: TagRepository
    private lateinit var sourcePhotos: PhotoRepository
    private lateinit var tempDir: File
    private lateinit var outputDir: File
    private var bookId: Long = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempDir = Files.createTempDirectory("kwbooktrack-zip-").toFile()
        outputDir = Files.createTempDirectory("kwbooktrack-zip-out-").toFile()

        sourceDb = TestDb.createInMemory(context)
        sourcePhotos = PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage)
        sourceBooks = BookRepository(sourceDb.bookDao(), sourceDb.tagDao(), NoOpCoverStorage, sourcePhotos)
        sourceNotes = NoteRepository(sourceDb.noteDao(), NoOpPhotoStorage)
        sourceSessions = SessionRepository(sourceDb.readingSessionDao())
        sourceTags = TagRepository(sourceDb.tagDao())
        exportUseCase = ExportZipUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = sourceBooks,
                noteDao = sourceDb.noteDao(),
                sessionDao = sourceDb.readingSessionDao(),
                photoDao = sourceDb.photoDao(),
                tagDao = sourceDb.tagDao(),
            ),
            versionProvider = object : AppVersionProvider {
                override val appVersion: String = "0.1.0"
            },
            database = sourceDb,
        )

        targetDb = TestDb.createInMemory(context)
        val targetPhotos = PhotoRepository(targetDb.photoDao(), FilePhotoStorage(context))
        val targetBooks = BookRepository(targetDb.bookDao(), targetDb.tagDao(), NoOpCoverStorage, targetPhotos)
        importUseCase = ImportKwBooktrackZipUseCase(
            SnapshotApplier(
                bookRepository = targetBooks,
                noteRepository = NoteRepository(targetDb.noteDao(), FilePhotoStorage(context)),
                sessionRepository = SessionRepository(targetDb.readingSessionDao()),
                tagRepository = TagRepository(targetDb.tagDao()),
                photoRepository = targetPhotos,
                photoStorage = FilePhotoStorage(context),
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
    fun exportArchiveHasExtraJsonPhotosAndNewCsvColumns() = runTest {
        seedLibrary()

        val file = File(tempDir, "backup.zip")
        exportUseCase(file)

        val entries = zipEntries(file)
        assertThat(entries.keys).contains("manifest.json")
        assertThat(entries.keys).contains("books.csv")
        assertThat(entries.keys).contains("extra.json")

        // Новые колонки — в конце заголовка, значения — из книги.
        val csv = String(entries.getValue("books.csv"), Charsets.UTF_8)
        assertThat(csv.lineSequence().first()).endsWith(";purchase_date;purchase_price;source;id;created_at;updated_at")
        val record = CSVParser(StringReader(csv), CSV_FORMAT).use { it.records.single() }
        assertThat(record.get("purchase_date")).isEqualTo("2023-12-01")
        assertThat(record.get("purchase_price")).isEqualTo("599,5")
        assertThat(record.get("source")).isEqualTo("MANUAL")
        assertThat(record.get("id")).isEqualTo(bookId.toString())
        // Метки времени — в миллисекундах epoch, как их хранит БД.
        val source = sourceDb.bookDao().observeAll().first().single()
        assertThat(record.get("created_at")).isEqualTo(source.createdAt.toString())
        assertThat(record.get("updated_at")).isEqualTo(source.updatedAt.toString())

        // extra.json — по id книги строкой.
        val extra = Json.parseToJsonElement(String(entries.getValue("extra.json"), Charsets.UTF_8)).jsonObject
        val book = extra.getValue("books").jsonObject.getValue(bookId.toString()).jsonObject

        val notes = book.getValue("notes").jsonArray
        assertThat(notes).hasSize(2)
        val quote = notes.first { it.jsonObject.getValue("kind").jsonPrimitive.content == "QUOTE" }.jsonObject
        assertThat(quote.getValue("page").jsonPrimitive.content).isEqualTo("12")
        assertThat(quote.getValue("body").jsonPrimitive.content).isEqualTo("Цитата")
        assertThat(quote.getValue("createdAt").jsonPrimitive.content).isEqualTo(QUOTE_AT.toString())
        val note = notes.first { it.jsonObject.getValue("kind").jsonPrimitive.content == "NOTE" }.jsonObject
        assertThat(note["page"]?.jsonPrimitive?.contentOrNull).isNull()
        assertThat(note.getValue("createdAt").jsonPrimitive.content).isEqualTo(NOTE_AT.toString())

        val session = book.getValue("sessions").jsonArray.single().jsonObject
        assertThat(session.getValue("startedAt").jsonPrimitive.content).isEqualTo(SESSION_AT.toString())
        assertThat(session.getValue("durationSec").jsonPrimitive.content).isEqualTo("600")

        assertThat(book.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("фэнтези")

        // Фото: путь внутри архива + 1-based индекс заметки (цитата — первая).
        val photo = book.getValue("photos").jsonArray.single().jsonObject
        val photoFile = photo.getValue("file").jsonPrimitive.content
        assertThat(photoFile).startsWith("photos/")
        assertThat(photoFile).endsWith(".png")
        assertThat(photo.getValue("noteIndex").jsonPrimitive.content).isEqualTo("1")
        assertThat(photo.getValue("createdAt").jsonPrimitive.content).isEqualTo(PHOTO_AT.toString())
        assertThat(entries.keys).contains(photoFile)

        val field = book.getValue("extraFields").jsonObject.getValue("посоветовал").jsonObject
        assertThat(field.getValue("t").jsonPrimitive.content).isEqualTo("Посоветовал")
        assertThat(field.getValue("v").jsonPrimitive.content).isEqualTo("Книжный поезд")
    }

    @Test
    fun importOfExportedArchiveKeepsEverything() = runTest {
        seedLibrary()
        val file = File(tempDir, "backup.zip")
        exportUseCase(file)

        val result = importUseCase(file, outputDir)

        assertThat(result.imported).isEqualTo(1)
        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(book.title).isEqualTo("Дюна")
        assertThat(book.author).isEqualTo("Фрэнк Герберт")
        assertThat(book.status).isEqualTo(BookStatus.FINISHED)
        assertThat(book.purchasePrice).isEqualTo(599.5)
        assertThat(book.purchaseDate).isEqualTo(day(2023, 12, 1))
        // Метки времени книги переносятся, а не ставятся в «сейчас».
        val source = sourceDb.bookDao().observeAll().first().single()
        assertThat(book.createdAt).isEqualTo(source.createdAt)
        assertThat(book.updatedAt).isEqualTo(source.updatedAt)
        assertThat(book.extraFields["посоветовал"]?.value).isEqualTo("Книжный поезд")

        val notes = targetDb.noteDao().observeByMedia(book.id, MediaType.BOOKS).first()
        assertThat(notes).hasSize(2)
        assertThat(notes.map { it.kind }.toSet()).containsExactly(NoteKind.QUOTE, NoteKind.NOTE)
        assertThat(notes.single { it.kind == NoteKind.QUOTE }.bodyMd).isEqualTo("Цитата")
        assertThat(notes.single { it.kind == NoteKind.QUOTE }.page).isEqualTo(12)
        // Даты заметок не переписываются на «сейчас».
        assertThat(notes.single { it.kind == NoteKind.QUOTE }.createdAt).isEqualTo(QUOTE_AT)
        assertThat(notes.single { it.kind == NoteKind.NOTE }.createdAt).isEqualTo(NOTE_AT)

        val sessions = targetDb.readingSessionDao().observeByBook(book.id).first()
        assertThat(sessions).hasSize(1)
        assertThat(sessions.single().durationSec).isEqualTo(600)

        assertThat(targetDb.tagDao().tagsOf(book.id).map { it.name }).containsExactly("фэнтези")

        val photos = targetDb.photoDao().observeByMedia(book.id, MediaType.BOOKS).first()
        assertThat(photos).hasSize(1)
        assertThat(photos.single().noteId).isEqualTo(notes.single { it.kind == NoteKind.QUOTE }.id)
        assertThat(File(photos.single().path).exists()).isTrue()

        // Обложка на месте: файл из архива лёг в каталог обложек.
        assertThat(book.coverPath).isNotNull()
        assertThat(File(book.coverPath!!).exists()).isTrue()
    }

    @Test
    fun reimportOfExportedArchiveDoesNotDuplicateAnything() = runTest {
        seedLibrary()
        val file = File(tempDir, "backup.zip")
        exportUseCase(file)

        importUseCase(file, outputDir)
        importUseCase(file, outputDir)

        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(targetDb.bookDao().observeAll().first()).hasSize(1)
        assertThat(targetDb.noteDao().observeByMedia(book.id, MediaType.BOOKS).first()).hasSize(2)
        assertThat(targetDb.readingSessionDao().observeByBook(book.id).first()).hasSize(1)
        assertThat(targetDb.tagDao().tagsOf(book.id)).hasSize(1)
        assertThat(targetDb.photoDao().observeByMedia(book.id, MediaType.BOOKS).first()).hasSize(1)
    }

    @Test
    fun photoWithoutFileIsSkippedWithoutBreakingExport() = runTest {
        seedLibrary()
        // Фото пропало с диска — экспорт не должен ни падать, ни врать о фото.
        File(tempDir, "photo.png").delete()

        val file = File(tempDir, "backup.zip")
        val manifest = exportUseCase(file)

        assertThat(manifest.counts.photos).isEqualTo(0)
        val entries = zipEntries(file)
        assertThat(entries.keys.none { it.startsWith("photos/") }).isTrue()
        val extra = Json.parseToJsonElement(String(entries.getValue("extra.json"), Charsets.UTF_8)).jsonObject
        val book = extra.getValue("books").jsonObject.getValue(bookId.toString()).jsonObject
        assertThat(book.getValue("photos").jsonArray).isEmpty()
    }

    /** Книга с двумя заметками, сессией, тегом, фото, обложкой и чужим полем. */
    private suspend fun seedLibrary() {
        val cover = File(tempDir, "cover.png").apply { writeBytes(pngBytes()) }
        bookId = sourceBooks.insert(
            BookEntity(
                title = "Дюна",
                author = "Фрэнк Герберт",
                isbn13 = "9780441013593",
                pageCount = 412,
                language = "ru",
                status = BookStatus.FINISHED,
                coverPath = cover.absolutePath,
                purchaseDate = day(2023, 12, 1),
                purchasePrice = 599.5,
                createdAt = day(2024, 1, 1),
                updatedAt = day(2024, 1, 20),
                extraFields = mapOf("посоветовал" to ExtraField("Посоветовал", "Книжный поезд")),
            ),
        )
        val quoteId = sourceNotes.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
                page = 12,
                bodyMd = "Цитата",
                kind = NoteKind.QUOTE,
                createdAt = QUOTE_AT,
                updatedAt = QUOTE_AT,
            ),
        )
        sourceNotes.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
                bodyMd = "Заметка",
                kind = NoteKind.NOTE,
                createdAt = NOTE_AT,
                updatedAt = NOTE_AT,
            ),
        )
        sourceSessions.insert(
            ReadingSessionEntity(
                bookId = bookId,
                startedAt = SESSION_AT,
                endedAt = SESSION_AT + 600_000,
                startPage = 1,
                endPage = 30,
                durationSec = 600,
                note = "Читал",
            ),
        )
        sourceTags.attachToMedia(bookId, MediaType.BOOKS, sourceTags.findOrCreate("фэнтези"))
        val photo = File(tempDir, "photo.png").apply { writeBytes(pngBytes(Color.BLUE)) }
        sourcePhotos.insert(
            PhotoEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
                noteId = quoteId,
                path = photo.absolutePath,
                createdAt = PHOTO_AT,
            ),
        )
    }

    private fun zipEntries(file: File): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Настоящий PNG — транскодер обложек декодирует по содержимому. */
    private fun pngBytes(color: Int = Color.RED): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun day(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).toEpochMs()

    private companion object {
        const val QUOTE_AT = 1_730_000_000_000L
        const val NOTE_AT = 1_730_000_100_000L
        const val SESSION_AT = 1_730_000_200_000L
        const val PHOTO_AT = 1_730_000_300_000L

        val CSV_FORMAT: CSVFormat = CSVFormat.Builder.create(CSVFormat.EXCEL)
            .setDelimiter(';')
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
    }
}
