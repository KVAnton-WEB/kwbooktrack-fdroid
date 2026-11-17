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
import org.w3c.dom.Element
import org.xml.sax.InputSource
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
import ru.antonkw.kwbooktrack.data.importer.ImportFormat
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoUseCase
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoZipUseCase
import ru.antonkw.kwbooktrack.data.importer.TellicoBookMapper
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
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
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.importer.TellicoMovieMapper

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
    private lateinit var sourceMovies: MovieRepository
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
        sourceMovies = MovieRepository(sourceDb.movieDao(), NoOpCoverStorage, sourcePhotos)

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
                    movieMapper = TellicoMovieMapper(),
                applier = SnapshotApplier(
                    bookRepository = targetBooks,
                movieRepository = MovieRepository(targetDb.movieDao(), NoOpCoverStorage, targetPhotos),
                    noteRepository = NoteRepository(targetDb.noteDao(), FilePhotoStorage(context)),
                    sessionRepository = SessionRepository(targetDb.readingSessionDao()),
                    tagRepository = TagRepository(targetDb.tagDao()),
                    photoRepository = targetPhotos,
                mediaDateRepository = MediaDateRepository(targetDb.mediaDateDao()),
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
    fun quoteRowsAreWrittenAsOneTable() = runTest {
        seedLibrary(withPageLessQuote = true)

        val xml = exportedXml()

        // Tellico пишет таблицу одним элементом, строки — переводом строки.
        // Иначе каждая цитата стала бы отдельной таблицей, а в Tellico
        // появился бы переключатель таблиц вместо одной таблицы с цитатами.
        assertThat(xml).contains(
            "<цитатыs><цитаты>12::Цитата про хаос\n::текст без страницы</цитаты></цитатыs>",
        )
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
    fun textPriceIsWrittenToItsOwnField() = runTest {
        // «По подписке» в `pur_price` Tellico не покажет: у него это число.
        sourceBooks.insert(
            BookEntity(
                title = "Подписка",
                author = "Кто-то",
                createdAt = 1L,
                updatedAt = 1L,
                extraFields = mapOf("pur_price" to ExtraField("Цена покупки", "по подписке")),
            ),
        )

        val xml = exportedXml()

        assertThat(xml).doesNotContain("<pur_price>")
        assertThat(xml).contains("<цена-покупки-текст>по подписке</цена-покупки-текст>")
        // Поле объявлено — иначе Tellico не покажет значение.
        assertThat(xml).contains(fieldTag("Покупка", 0, 4, "цена-покупки-текст", "Цена покупки (текст)", 1))
    }

    @Test
    fun builtinTellicoFieldsAreDeclaredOnce() = runTest {
        // «Содержание», «Редакция», «Подписано» — стандартные поля Tellico, но
        // объявлять их надо: при импорте Tellico берёт поля только из `<fields>`,
        // иначе значение молча выбрасывается. Дубликата не будет — объявление
        // и есть поле.
        sourceBooks.insert(
            BookEntity(
                title = "Дюна",
                author = "Фрэнк Герберт",
                createdAt = 1L,
                updatedAt = 1L,
                extraFields = mapOf(
                    "plot" to ExtraField("Содержание", "Глава 1"),
                    "edition" to ExtraField("Редакция", "2-е изд."),
                    "editor" to ExtraField("Редактор", "Иванов"),
                    "signed" to ExtraField("Подписано", "да"),
                ),
            ),
        )

        val xml = exportedXml()

        assertThat(xml).contains(fieldTag("Содержание", 0, 4, "plot", "Содержание", 2))
        assertThat(xml).contains(fieldTag("Издательство", 4, 0, "edition", "Редакция", 1))
        assertThat(xml).contains(fieldTag("Личное", 0, 4, "signed", "Подписано", 4))
        // Объявлено ровно один раз — дубликата у пользователя не появится.
        assertThat(Regex("name=\"plot\"").findAll(xml).count()).isEqualTo(1)
        assertThat(Regex("name=\"edition\"").findAll(xml).count()).isEqualTo(1)
        assertThat(Regex("name=\"signed\"").findAll(xml).count()).isEqualTo(1)
        // Значение встроенного поля всё равно пишется — в его элемент.
        assertThat(xml).contains("<plot>Глава 1</plot>")
        assertThat(xml).contains("<edition>2-е изд.</edition>")
        // «Редактор» у Tellico многозначный — контейнером.
        assertThat(xml).contains("<editors><editor>Иванов</editor></editors>")
        // Флаг Tellico ждёт `true`/`false`, а не слово.
        assertThat(xml).contains("<signed>true</signed>")
    }

    @Test
    fun textPriceSurvivesExportAndImport() = runTest {
        sourceBooks.insert(
            BookEntity(
                title = "Подписка",
                author = "Кто-то",
                createdAt = 1L,
                updatedAt = 1L,
                extraFields = mapOf("pur_price" to ExtraField("Цена покупки", "по подписке")),
            ),
        )
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(book.purchasePrice).isNull()
        assertThat(book.extraFields["pur_price"]?.value).isEqualTo("по подписке")
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

        val notes = targetDb.noteDao().observeByMedia(book.id, MediaType.BOOKS).first()
        assertThat(notes).hasSize(3)
        assertThat(notes.map { it.kind }.toSet()).containsExactly(NoteKind.QUOTE, NoteKind.REVIEW, NoteKind.NOTE)
        assertThat(notes.single { it.kind == NoteKind.QUOTE }.page).isEqualTo(12)
        // Перевод строки внутри заметки переживает экспорт (экранирование ячейки).
        val note = notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.bodyMd).isEqualTo("Заметка\nс переносом")
        assertThat(note.createdAt).isEqualTo(day(2024, 1, 12))

        assertThat(targetDb.readingSessionDao().observeByBook(book.id).first()).hasSize(1)
        assertThat(targetDb.tagDao().tagsOf(book.id).map { it.name }).containsExactly("фэнтези")
        val photo = targetDb.photoDao().observeByMedia(book.id, MediaType.BOOKS).first().single()
        assertThat(File(photo.path).exists()).isTrue()
    }

    @Test
    fun roundTripKeepsEveryBookField() = runTest {
        seedLibrary()
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val source = sourceDb.bookDao().observeAll().first().single()
        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(book.title).isEqualTo("Дюна")
        assertThat(book.subtitle).isEqualTo("Песни пустыни")
        assertThat(book.author).isEqualTo("Фрэнк Герберт")
        assertThat(book.isbn13).isEqualTo("9780441013593")
        assertThat(book.pageCount).isEqualTo(412)
        assertThat(book.language).isEqualTo("ru")
        assertThat(book.genre).isEqualTo("Фантастика")
        assertThat(book.publisher).isEqualTo("Азбука")
        assertThat(book.year).isEqualTo(2019)
        assertThat(book.rating).isEqualTo(5)
        assertThat(book.status).isEqualTo(BookStatus.FINISHED)
        assertThat(book.startedAt).isEqualTo(day(2024, 1, 10))
        assertThat(book.finishedAt).isEqualTo(day(2024, 1, 20))
        assertThat(book.purchaseDate).isEqualTo(day(2023, 12, 1))
        assertThat(book.purchasePrice).isEqualTo(599.0)
        assertThat(book.binding).isEqualTo("Твёрдый")
        assertThat(book.purchasePlace).isEqualTo("Магазин на Лесной")
        assertThat(book.series).isEqualTo("Дюна")
        assertThat(book.seriesNumber).isEqualTo("1")
        assertThat(book.condition).isEqualTo("Новая")
        assertThat(book.translator).isEqualTo("В. Иванов")
        assertThat(book.printYear).isEqualTo(2018)
        assertThat(book.printPages).isEqualTo(32.5)
        assertThat(book.originalLanguage).isEqualTo("en")
        assertThat(book.originalTitle).isEqualTo("Dune")
        assertThat(book.annotation).isEqualTo("Аннотация")
        assertThat(book.isGift).isTrue()
        assertThat(book.isLent).isTrue()
        assertThat(book.isSold).isTrue()
        assertThat(book.extraFields["чтец"]?.value).isEqualTo("Иванова")
        // Метки времени Tellico — даты, поэтому время суток не переносится.
        assertThat(book.createdAt).isEqualTo(day(2024, 1, 1))
        assertThat(book.updatedAt).isEqualTo(source.updatedAt.toLocalDate().toEpochMs())
    }

    @Test
    fun fieldsBlockDeclaresOwnFieldsWithExpectedTypes() = runTest {
        seedLibrary()

        val xml = exportedXml()

        // Свои поля объявляем с теми же типами, что и в живой коллекции Tellico:
        // «усл.-печ.-л.» — текст (1), а не число (6).
        assertThat(xml).contains(fieldTag("Личное", 2, 4, "брошено", "Брошено", 4))
        assertThat(xml).contains(fieldTag("Личное", 0, 3, "дата-начала", "Дата начала", 12))
        assertThat(xml).contains(fieldTag("Личное", 0, 3, "дата-прочтения", "Дата прочтения", 12))
        assertThat(xml).contains(fieldTag("Личное", 2, 4, "прочитал-часть", "Прочитал часть", 4))
        assertThat(xml).contains(fieldTag("Покупка", 0, 4, "место-покупки", "Место покупки", 1))
        assertThat(xml).contains(fieldTag("Личное", 2, 4, "продано-new", "Продано?", 4))
        assertThat(xml).contains(fieldTag("Издательство", 0, 4, "усл.-печ.-л.", "Усл. печ. л.", 1))
        assertThat(xml).contains(fieldTag("Издательство", 0, 4, "язык-оригинала", "Язык оригинала", 1))
        assertThat(xml).contains(fieldTag("Общее", 0, 4, "оригинальное-название", "Оригинальное название", 1))
        assertThat(xml).contains(fieldTag("Цитаты", 1, 4, "цитаты", "Цитаты", 2))
        assertThat(xml).contains(fieldTag("Рецензия", 0, 4, "рецензия", "Рецензия", 2))
        assertThat(xml).contains(fieldTag("Личное", 1, 4, "заметки", "Заметки", 2))
        assertThat(xml).contains(fieldTag("Классификация", 1, 4, "теги", "Теги", 1))
        assertThat(xml).contains(fieldTag("Личное", 1, 4, "сессии-чтения", "Сессии чтения", 2))
        assertThat(xml).contains(fieldTag("Личное", 1, 4, "фото-заметок", "Фото заметок", 2))

        // Табличные поля объявляют число колонок.
        assertThat(xml).contains("<prop name=\"columns\">3</prop>")
        assertThat(xml).contains("<prop name=\"columns\">6</prop>")

        // Чужие поля объявляем текстом (1) — встроенные поля Tellico знает сам.
        assertThat(xml).contains(fieldTag("Общее", 0, 4, "чтец", "Чтец", 1))
    }

    @Test
    fun everyFieldUsedInEntryIsDeclaredInFieldsBlock() = runTest {
        seedLibrary()

        val xml = exportedXml()
        val (declared, used) = parseFieldUsage(xml)

        // Tellico при импорте берёт поля только из `<fields>`, а значения
        // необъявленных полей молча выбрасывает — записи выходят пустыми.
        // Контейнер многозначного поля — имя поля с `s` на конце.
        val undeclared = used.filter { name ->
            name !in declared && name.removeSuffix("s") !in declared
        }
        assertThat(undeclared).isEmpty()

        // Стандартные поля Tellico без объявления терялись все до единого.
        assertThat(declared).containsAtLeast(
            "title", "author", "publisher", "isbn", "pages", "comments",
            "cover", "cdate", "mdate", "rating", "genre", "language",
        )
    }

    @Test
    fun photoStaysBoundToItsNoteAfterRoundTrip() = runTest {
        seedLibrary()
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val book = targetDb.bookDao().observeAll().first().single()
        val notes = targetDb.noteDao().observeByMedia(book.id, MediaType.BOOKS).first()
        val photo = targetDb.photoDao().observeByMedia(book.id, MediaType.BOOKS).first().single()
        // Фото привязано к цитате — первой заметке снимка (цитата → рецензия → заметка).
        assertThat(photo.noteId).isEqualTo(notes.single { it.kind == NoteKind.QUOTE }.id)
        assertThat(File(photo.path).exists()).isTrue()
    }

    @Test
    fun reimportOfExportedFileDoesNotDuplicateAnything() = runTest {        seedLibrary()
        val file = File(tempDir, "library.tc")
        exportUseCase(file)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)
        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val book = targetDb.bookDao().observeAll().first().single()
        assertThat(targetDb.bookDao().observeAll().first()).hasSize(1)
        assertThat(targetDb.noteDao().observeByMedia(book.id, MediaType.BOOKS).first()).hasSize(3)
        assertThat(targetDb.readingSessionDao().observeByBook(book.id).first()).hasSize(1)
        assertThat(targetDb.tagDao().tagsOf(book.id)).hasSize(1)
        assertThat(targetDb.photoDao().observeByMedia(book.id, MediaType.BOOKS).first()).hasSize(1)
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
                subtitle = "Песни пустыни",
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
                isGift = true,
                isLent = true,
                isSold = true,
                extraFields = mapOf("чтец" to ExtraField("Чтец", "Иванова")),
            ),
        )
        val quoteId = sourceNotes.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
                page = 12,
                bodyMd = "Цитата про хаос",
                kind = NoteKind.QUOTE,
                createdAt = day(2024, 1, 11),
                updatedAt = day(2024, 1, 11),
            ),
        )
        sourceNotes.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
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
                    mediaId = bookId, mediaType = MediaType.BOOKS,
                    bodyMd = "текст без страницы",
                    kind = NoteKind.QUOTE,
                    createdAt = day(2024, 1, 16),
                    updatedAt = day(2024, 1, 16),
                ),
            )
        }
        sourceNotes.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
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
        sourceTags.attachToMedia(bookId, MediaType.BOOKS, sourceTags.findOrCreate("фэнтези"))
        val photo = File(tempDir, "photo.png").apply { writeBytes(pngBytes()) }
        sourcePhotos.insert(
            PhotoEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
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

    /** `tellico.xml` уже выгруженного архива. */
    private fun exportedXml(file: File): String =
        String(zipEntries(file).getValue("tellico.xml"), Charsets.UTF_8)

    /** Экспортёр над указанной базой — нужен, чтобы переэкспортировать импортированное. */
    @Test
    fun exportOfMoviesWritesMovieCollection() = runTest {
        seedMovies()

        val file = File(tempDir, "movies.tc")
        exportUseCase(file, MediaType.MOVIES)

        val xml = exportedXml(file)
        assertThat(xml).contains("<collection title=\"kwbooktrack\" type=\"3\">")
        // В файле одна коллекция: Tellico держит одну и при двух взял бы
        // последнюю, смешав записи.
        assertThat(xml).doesNotContain("<collection title=\"kwbooktrack\" type=\"2\">")
    }

    @Test
    fun exportOfMoviesDeclaresNotesTagsAndPhotos() = runTest {
        // Без объявления наших полей Tellico молча выбросил бы значения
        // заметок, тегов и фото при импорте нашей выгрузки.
        seedMovies()

        val xml = exportedXml(File(tempDir, "movies.tc").also { exportUseCase(it, MediaType.MOVIES) })

        assertThat(xml).contains("name=\"цитаты\"")
        assertThat(xml).contains("name=\"заметки\"")
        assertThat(xml).contains("name=\"теги\"")
        assertThat(xml).contains("name=\"фото-заметок\"")
    }

    @Test
    fun exportOfMoviesWritesCastAsTellicoTable() = runTest {
        seedMovies()

        val xml = exportedXml(File(tempDir, "movies.tc").also { exportUseCase(it, MediaType.MOVIES) })

        // У Tellico `cast` — таблица из двух колонок: актёр и роль лежат
        // отдельными <column>.
        assertThat(xml).contains("<cast><column>Уоттс, Наоми</column><column>Роль</column></cast>")
    }

    @Test
    fun everyFieldUsedInMovieEntryIsDeclaredInFieldsBlock() = runTest {
        // Tellico при импорте берёт поля только из `<fields>`, а значения
        // необъявленных полей молча выбрасывает. Имена из книжного словаря
        // (`author`) в фильмовой коллекции чужие — их надо объявлять, иначе
        // значение терялось бы по дороге в Tellico.
        sourceMovies.insert(
            MovieEntity(
                title = "Синий бархат",
                year = 1989,
                extraFields = mapOf("author" to ExtraField("Автор", "Линч, Дэвид")),
                createdAt = day(2024, 1, 1),
                updatedAt = day(2024, 1, 1),
            ),
        )

        val xml = exportedXml(File(tempDir, "movies.tc").also { exportUseCase(it, MediaType.MOVIES) })

        val (declared, used) = parseFieldUsage(xml)
        val undeclared = used.filter { name ->
            name !in declared && name.removeSuffix("s") !in declared
        }
        assertThat(undeclared).isEmpty()
        assertThat(declared).contains("author")
    }

    @Test
    fun roundTripOfMovieKeepsFields() = runTest {
        seedMovies()
        val file = File(tempDir, "movies.tc")
        exportUseCase(file, MediaType.MOVIES)

        val result = importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.movies).isEqualTo(1)
        val movie = targetDb.movieDao().observeAll().first().single()
        assertThat(movie.title).isEqualTo("Малхолланд Драйв")
        assertThat(movie.originalTitle).isEqualTo("Mulholland Dr")
        assertThat(movie.year).isEqualTo(1999)
        assertThat(movie.genre).isEqualTo("Триллер")
        assertThat(movie.nationality).isEqualTo("США")
        assertThat(movie.director).isEqualTo("Линч, Дэвид")
        assertThat(movie.durationMin).isEqualTo(147)
        assertThat(movie.rating).isEqualTo(5)
        assertThat(movie.url).isEqualTo("https://www.kinopoisk.ru/film/466/")
        assertThat(movie.isDownloaded).isTrue()
        assertThat(movie.watchedAt).isEqualTo(day(2024, 2, 22))
        assertThat(File(movie.coverPath!!).exists()).isTrue()
    }

    @Test
    fun roundTripOfMovieKeepsCastReviewAndNotes() = runTest {
        seedMovies()
        val file = File(tempDir, "movies.tc")
        exportUseCase(file, MediaType.MOVIES)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        val movie = targetDb.movieDao().observeAll().first().single()
        assertThat(movie.cast).isEqualTo("Уоттс, Наоми::Роль")
        val notes = targetDb.noteDao().observeByMedia(movie.id, MediaType.MOVIES).first()
        assertThat(notes.map { it.kind }.toSet()).containsExactly(NoteKind.REVIEW, NoteKind.NOTE)
        assertThat(notes.single { it.kind == NoteKind.REVIEW }.bodyMd).isEqualTo("Отличный фильм.")
        assertThat(targetDb.tagDao().tagsForAllMedia().filter { it.mediaId == movie.id }.map { it.name })
            .containsExactly("нуар")
    }

    @Test
    fun roundTripOfMovieDoesNotDuplicateOnSecondImport() = runTest {
        seedMovies()
        val file = File(tempDir, "movies.tc")
        exportUseCase(file, MediaType.MOVIES)

        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)
        importUseCase(file, ImportFormat.TELLICO_XML, outputDir)

        assertThat(targetDb.movieDao().count()).isEqualTo(1)
    }

    private suspend fun seedMovies() {
        val cover = File(tempDir, "movie-cover.png").apply { writeBytes(pngBytes()) }
        val movieId = sourceMovies.insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                originalTitle = "Mulholland Dr",
                year = 1999,
                genre = "Триллер",
                nationality = "США",
                director = "Линч, Дэвид",
                cast = "Уоттс, Наоми::Роль",
                durationMin = 147,
                rating = 5,
                watchedAt = day(2024, 2, 22),
                coverPath = cover.absolutePath,
                plot = "Молодая актриса приезжает в Голливуд.",
                url = "https://www.kinopoisk.ru/film/466/",
                isDownloaded = true,
                createdAt = day(2024, 1, 1),
                updatedAt = day(2024, 2, 22),
            ),
        )
        sourceNotes.insert(
            NoteEntity(
                mediaId = movieId, mediaType = MediaType.MOVIES,
                bodyMd = "Отличный фильм.",
                kind = NoteKind.REVIEW,
                createdAt = day(2024, 2, 23),
                updatedAt = day(2024, 2, 23),
            ),
        )
        sourceNotes.insert(
            NoteEntity(
                mediaId = movieId, mediaType = MediaType.MOVIES,
                bodyMd = "Пересмотреть весной.",
                kind = NoteKind.NOTE,
                createdAt = day(2024, 2, 23),
                updatedAt = day(2024, 2, 23),
            ),
        )
        val tagId = sourceDb.tagDao().findOrCreate("нуар")
        sourceDb.tagDao().insertCrossRef(MediaTagCrossRef(mediaId = movieId, mediaType = MediaType.MOVIES, tagId = tagId))
    }

    private fun exporterFor(db: KwBooktrackDatabase, books: BookRepository): ExportTellicoTcUseCase =
        ExportTellicoTcUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = books,
            movieRepository = MovieRepository(db.movieDao(), NoOpCoverStorage, PhotoRepository(db.photoDao(), NoOpPhotoStorage)),
                noteDao = db.noteDao(),
                sessionDao = db.readingSessionDao(),
                photoDao = db.photoDao(),
                tagDao = db.tagDao(),
                mediaDateDao = sourceDb.mediaDateDao(),
            ),
            database = db,
            versionProvider = object : AppVersionProvider {
                override val appVersion: String = "0.1.0"
            },
        )

    /** Цитаты импортированной книги. */
    private suspend fun targetQuotes(): List<NoteEntity> {
        val book = targetDb.bookDao().observeAll().first().single()
        return targetDb.noteDao().observeByMedia(book.id, MediaType.BOOKS).first().filter { it.kind == NoteKind.QUOTE }
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

    /** Начало объявления поля в `<fields>` — как его пишет TellicoXmlWriter. */
    private fun fieldTag(
        category: String,
        flags: Int,
        format: Int,
        name: String,
        title: String,
        type: Int,
    ): String =
        "<field category=\"$category\" description=\"\" flags=\"$flags\" format=\"$format\" " +
            "name=\"$name\" title=\"$title\" type=\"$type\""

    /** Объявленные в `<fields>` имена полей и имена элементов, которыми заполнены записи. */
    private fun parseFieldUsage(xml: String): Pair<Set<String>, Set<String>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
        }
        val document = factory.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val collection = document.getElementsByTagName("collection").item(0) as Element
        val fields = collection.getElementsByTagName("field")
        val declared = (0 until fields.length)
            .map { (fields.item(it) as Element).getAttribute("name") }
            .toSet()
        val entries = collection.getElementsByTagName("entry")
        val used = mutableSetOf<String>()
        for (index in 0 until entries.length) {
            val children = (entries.item(index) as Element).childNodes
            for (child in 0 until children.length) {
                (children.item(child) as? Element)?.let { used += it.tagName }
            }
        }
        return declared to used
    }
}
