package ru.antonkw.kwbooktrack.data.exporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.importer.ImportFormat
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoUseCase
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoZipUseCase
import ru.antonkw.kwbooktrack.data.importer.TellicoBookMapper
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
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Экспорт библиотеки в `.tc` (zip с `tellico.xml`, обложками и фото) и обратный
 * импорт: экспорт не теряет данные, повторный импорт не плодит дубли.
 */
@RunWith(RobolectricTestRunner::class)
class TellicoTcRoundTripTest {

    private lateinit var sourceDb: KwBooktrackDatabase
    private lateinit var targetDb: KwBooktrackDatabase
    private lateinit var exportUseCase: ExportTellicoTcUseCase
    private lateinit var targetExportUseCase: ExportTellicoTcUseCase
    private lateinit var importUseCase: ImportTellicoZipUseCase
    private lateinit var sourceBooks: BookRepository
    private lateinit var sourceNotes: NoteRepository
    private lateinit var sourceSessions: SessionRepository
    private lateinit var sourceTags: TagRepository
    private lateinit var sourcePhotos: PhotoRepository
    private lateinit var tempDir: File
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = TestDb.createInMemory(context)
        targetDb = TestDb.createInMemory(context)

        sourcePhotos = PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage)
        sourceBooks = BookRepository(sourceDb.bookDao(), sourceDb.tagDao(), NoOpCoverStorage, sourcePhotos)
        sourceNotes = NoteRepository(sourceDb.noteDao(), NoOpPhotoStorage)
        sourceSessions = SessionRepository(sourceDb.readingSessionDao())
        sourceTags = TagRepository(sourceDb.tagDao())

        exportUseCase = exporterFor(sourceDb, sourceBooks)
        targetExportUseCase = exporterFor(
            targetDb,
            BookRepository(
                targetDb.bookDao(),
                targetDb.tagDao(),
                NoOpCoverStorage,
                PhotoRepository(targetDb.photoDao(), FilePhotoStorage(context)),
            ),
        )

        val targetPhotos = PhotoRepository(targetDb.photoDao(), FilePhotoStorage(context))
        val targetBooks = BookRepository(targetDb.bookDao(), targetDb.tagDao(), NoOpCoverStorage, targetPhotos)
        importUseCase = ImportTellicoZipUseCase(
            importUseCase = ImportTellicoUseCase(
                csvReader = TellicoCsvReader(),
                mapper = TellicoBookMapper(),
                applier = SnapshotApplier(
                    bookRepository = targetBooks,
                    noteRepository = NoteRepository(targetDb.noteDao(), FilePhotoStorage(context)),
                    sessionRepository = SessionRepository(targetDb.readingSessionDao()),
                    tagRepository = TagRepository(targetDb.tagDao()),
                    photoRepository = targetPhotos,
                    photoStorage = FilePhotoStorage(context),
                    transcoder = CoverTranscoder(),
                ),
            ),
            csvReader = TellicoCsvReader(),
            xmlReader = TellicoXmlReader(),
        )

        tempDir = Files.createTempDirectory("kwbooktrack-tc-").toFile()
        outputDir = Files.createTempDirectory("kwbooktrack-tc-out-").toFile()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        tempDir.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun exportWritesTcArchiveWithXmlCoversAndPhotos() = runTest {
        seedLibrary()

        val file = File(tempDir, "library.tc")
        val manifest = exportUseCase(file)

        assertThat(manifest.counts.books).isEqualTo(1)
        assertThat(manifest.counts.notes).isEqualTo(1)
        assertThat(manifest.counts.quotes).isEqualTo(1)
        assertThat(manifest.counts.sessions).isEqualTo(1)
        assertThat(manifest.counts.photos).isEqualTo(1)
        assertThat(manifest.counts.tags).isEqualTo(1)

        val entries = zipEntries(file)
        assertThat(entries.keys).contains("tellico.xml")
        assertThat(entries.keys.any { it.startsWith("images/") }).isTrue()
        assertThat(entries.keys.any { it.startsWith("photos/") }).isTrue()
    }

    @Test
    fun tellicoXmlHasTellicoSkeletonAndMultiValueContainers() = runTest {
        seedLibrary()

        val xml = exportedXml()

        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(xml).contains("<tellico xmlns=\"http://periapsis.org/tellico/\" syntaxVersion=\"12\">")
        assertThat(xml).contains("<collection title=\"kwbooktrack\" type=\"2\">")
        // Многозначные поля — контейнерами: плоская запись Tellico читает как одно значение.
        assertThat(xml).contains("<authors><author>Фрэнк Герберт</author></authors>")
        assertThat(xml).contains("<genres><genre>Фантастика</genre></genres>")
        assertThat(xml).contains("<languages><language>ru</language></languages>")
        assertThat(xml).contains("<тегиs><теги>фэнтези</теги></тегиs>")
    }

    @Test
    fun imageElementHasOnlyIdAndFormat() = runTest {
        seedLibrary()

        val xml = exportedXml()

        // width/height в DTD #IMPLIED, но без них Tellico не показывает обложку.
        assertThat(xml).containsMatch("<image id=\"[^\"]+\" format=\"[^\"]+\"/>")
        assertThat(xml).doesNotContain("width=")
        assertThat(xml).doesNotContain("height=")
    }

    @Test
    fun exportWithoutCoversOmitsImagesBlock() = runTest {
        seedLibrary(withCover = false)

        val xml = exportedXml()

        // Пустой <images/> невалиден — блока не должно быть вовсе.
        assertThat(xml).doesNotContain("<images>")
    }

    @Test
    fun importOfExportedFileKeepsEverything() = runTest {
        seedLibrary()
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        val result = importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)
        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(book.title).isEqualTo("Дюна")
        assertThat(book.author).isEqualTo("Фрэнк Герберт")
        assertThat(book.status).isEqualTo(BookStatus.FINISHED)
        assertThat(book.isbn13).isEqualTo("9780441013593")
        assertThat(book.purchasePlace).isEqualTo("Магазин на Лесной")
        assertThat(book.extraFields["чтец"]?.value).isEqualTo("Иванова")
        assertThat(File(book.coverPath!!).exists()).isTrue()

        val notes = targetDb.noteDao().observeByBook(book.id).first()
        assertThat(notes).hasSize(3)
        assertThat(notes.map { it.kind }.toSet()).containsExactly(NoteKind.QUOTE, NoteKind.REVIEW, NoteKind.NOTE)
        assertThat(notes.single { it.kind == NoteKind.QUOTE }.page).isEqualTo(12)
        // Перевод строки внутри заметки переживает экспорт (экранирование ячейки).
        val note = notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.bodyMd).isEqualTo("Заметка\nс переносом")
        assertThat(note.createdAt).isEqualTo(day(2024, 1, 12))

        assertThat(targetDb.readingSessionDao().observeByBook(book.id).first()).hasSize(1)
        assertThat(targetDb.tagDao().tagsForBook(book.id).map { it.name }).containsExactly("фэнтези")
        val photo = targetDb.photoDao().observeByBook(book.id).first().single()
        assertThat(File(photo.path).exists()).isTrue()
    }

    @Test
    fun reimportOfExportedFileDoesNotDuplicateAnything() = runTest {
        seedLibrary()
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)
        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(targetDb.bookDao().observeAll().first()).hasSize(1)
        assertThat(targetDb.noteDao().observeByBook(book.id).first()).hasSize(3)
        assertThat(targetDb.readingSessionDao().observeByBook(book.id).first()).hasSize(1)
        assertThat(targetDb.tagDao().tagsForBook(book.id)).hasSize(1)
        assertThat(targetDb.photoDao().observeByBook(book.id).first()).hasSize(1)
    }

    @Test
    fun quoteWithoutPageDoesNotAccumulateColonsOnRepeatedRoundTrip() = runTest {
        seedLibrary(withPageLessQuote = true)
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val quote = targetQuotes().single { it.page == null }
        assertThat(quote.bodyMd).isEqualTo("текст без страницы")

        // Второй круг: экспорт уже импортированной книги и импорт снова.
        val second = File(tempDir, "library-2.tc")
        targetExportUseCase(second)
        importUseCase(second, ImportFormat.TELLICO_XML, outputDir)

        val quoteAfter = targetQuotes().single { it.page == null }
        assertThat(quoteAfter.bodyMd).isEqualTo("текст без страницы")
    }

    @Test
    fun tableFieldsDeclareColumnTitles() = runTest {
        seedLibrary()

        val xml = exportedXml()

        // У табличного поля Tellico подписи колонок обязательны (как `columns=N`).
        assertThat(xml).contains("<prop name=\"columns\">6</prop>")
        assertThat(xml).contains("<prop name=\"column1\">начало</prop>")
        assertThat(xml).contains("<prop name=\"column3\">страница начала</prop>")
        assertThat(xml).contains("<prop name=\"column6\">заметка</prop>")
        assertThat(xml).contains("<prop name=\"column1\">файл</prop>")
        assertThat(xml).contains("<prop name=\"column2\">индекс заметки</prop>")
        assertThat(xml).contains("<prop name=\"column3\">дата</prop>")
    }

    @Test
    fun foreignFieldWithUnsafeNameIsSkipped() = runTest {
        sourceBooks.insert(
            BookEntity(
                title = "Кривые поля",
                author = "Кто-то",
                createdAt = 1L,
                updatedAt = 1L,
                extraFields = mapOf(
                    "поле2" to ExtraField("Поле 2", "разрешено"),
                    "café" to ExtraField("Кафе", "тоже разрешено"),
                    "Ελλάδα" to ExtraField("Греция", "и это разрешено"),
                    "2-комментария" to ExtraField("Цифра", "запрещено"),
                    ".поле" to ExtraField("Точка", "запрещено"),
                    "-поле" to ExtraField("Дефис", "запрещено"),
                    "xmlsomething" to ExtraField("Xml", "запрещено"),
                ),
            ),
        )

        val xml = exportedXml()

        // Имя элемента экранировать нельзя — поле с небезопасным именем пропускаем.
        assertThat(xml).doesNotContain("2-комментария")
        assertThat(xml).doesNotContain(".поле")
        assertThat(xml).doesNotContain("-поле")
        assertThat(xml).doesNotContain("xmlsomething")
        // Валидные имена — в том числе не-ASCII — пишутся.
        assertThat(xml).contains("<поле2>разрешено</поле2>")
        assertThat(xml).contains("<café>тоже разрешено</café>")
        assertThat(xml).contains("<Ελλάδα>и это разрешено</Ελλάδα>")

        // XML остался целым: читается и содержит только безопасные поля.
        val records = TellicoXmlReader().read(xml.toByteArray())
        assertThat(records).hasSize(1)
        assertThat(records.single().extra.keys).containsExactly("поле2", "café", "Ελλάδα")
    }

    @Test
    fun ratingOutOfRangeIsNotWritten() = runTest {
        sourceBooks.insert(
            BookEntity(
                title = "Без рейтинга",
                author = "Кто-то",
                rating = 0,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val xml = exportedXml()

        // У Tellico рейтинг 1..5.
        assertThat(xml).doesNotContain("<rating>")
    }

    /** Книга с заметками (цитата, рецензия, заметка), сессией, тегом, фото, обложкой и чужим полем. */
    private suspend fun seedLibrary(withCover: Boolean = true, withPageLessQuote: Boolean = false) {
        val cover = File(tempDir, "cover.png").apply { writeBytes(pngBytes()) }
        val bookId = sourceBooks.insert(
            BookEntity(
                title = "Дюна",
                author = "Фрэнк Герберт",
                isbn13 = "9780441013593",
                pageCount = 412,
                language = "ru",
                genre = "Фантастика",
                publisher = "Азбука",
                year = 2019,
                coverPath = if (withCover) cover.absolutePath else null,
                rating = 5,
                status = BookStatus.FINISHED,
                startedAt = day(2024, 1, 10),
                finishedAt = day(2024, 1, 20),
                createdAt = day(2024, 1, 1),
                updatedAt = day(2024, 1, 20),
                purchaseDate = day(2023, 12, 1),
                purchasePrice = 599.0,
                binding = "Твёрдый",
                purchasePlace = "Магазин на Лесной",
                series = "Дюна",
                seriesNumber = "1",
                condition = "Новая",
                translator = "В. Иванов",
                printYear = 2018,
                printPages = 32.5,
                originalLanguage = "en",
                originalTitle = "Dune",
                annotation = "Аннотация",
                extraFields = mapOf("чтец" to ExtraField("Чтец", "Иванова")),
            ),
        )
        val quoteId = sourceNotes.insert(
            NoteEntity(
                bookId = bookId,
                page = 12,
                bodyMd = "Цитата про хаос",
                kind = NoteKind.QUOTE,
                createdAt = day(2024, 1, 11),
                updatedAt = day(2024, 1, 11),
            ),
        )
        sourceNotes.insert(
            NoteEntity(
                bookId = bookId,
                bodyMd = "Отзыв",
                kind = NoteKind.REVIEW,
                createdAt = day(2024, 1, 15),
                updatedAt = day(2024, 1, 15),
            ),
        )
        if (withPageLessQuote) {
            // Цитата без страницы: экспорт пишет `::текст`, импорт должен вернуть
            // текст без префикса — иначе каждый цикл добавлял бы ещё `::`.
            sourceNotes.insert(
                NoteEntity(
                    bookId = bookId,
                    bodyMd = "текст без страницы",
                    kind = NoteKind.QUOTE,
                    createdAt = day(2024, 1, 16),
                    updatedAt = day(2024, 1, 16),
                ),
            )
        }
        sourceNotes.insert(
            NoteEntity(
                bookId = bookId,
                page = 30,
                bodyMd = "Заметка\nс переносом",
                kind = NoteKind.NOTE,
                createdAt = day(2024, 1, 12),
                updatedAt = day(2024, 1, 12),
            ),
        )
        sourceSessions.insert(
            ReadingSessionEntity(
                bookId = bookId,
                startedAt = 1_730_000_000_000L,
                endedAt = 1_730_000_600_000L,
                startPage = 1,
                endPage = 30,
                durationSec = 600,
                note = "Читал",
            ),
        )
        sourceTags.attachToBook(bookId, sourceTags.findOrCreate("фэнтези"))
        val photo = File(tempDir, "photo.png").apply { writeBytes(pngBytes()) }
        sourcePhotos.insert(
            PhotoEntity(
                bookId = bookId,
                noteId = quoteId,
                path = photo.absolutePath,
                createdAt = day(2024, 1, 13),
            ),
        )
    }

    private suspend fun exportedXml(): String {
        val file = File(tempDir, "library.tc")
        exportUseCase(file)
        return String(zipEntries(file).getValue("tellico.xml"), Charsets.UTF_8)
    }

    /** Экспортёр над указанной базой — нужен, чтобы переэкспортировать импортированное. */
    private fun exporterFor(db: KwBooktrackDatabase, books: BookRepository): ExportTellicoTcUseCase =
        ExportTellicoTcUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = books,
                noteDao = db.noteDao(),
                sessionDao = db.readingSessionDao(),
                photoDao = db.photoDao(),
                tagDao = db.tagDao(),
            ),
            database = db,
            versionProvider = object : AppVersionProvider {
                override val appVersion: String = "0.1.0"
            },
        )

    /** Цитаты импортированной книги. */
    private suspend fun targetQuotes(): List<NoteEntity> {
        val book = targetDb.bookDao().observeAll().first().single()
        return targetDb.noteDao().observeByBook(book.id).first().filter { it.kind == NoteKind.QUOTE }
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
    private fun pngBytes(w: Int = 8, h: Int = 8): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.RED)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun day(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).toEpochMs()
}
