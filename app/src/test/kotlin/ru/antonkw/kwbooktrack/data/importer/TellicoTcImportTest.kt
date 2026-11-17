package ru.antonkw.kwbooktrack.data.importer

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
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.tagsOf
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.storage.FilePhotoStorage
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.importer.TellicoMovieMapper

/**
 * Импорт родного архива Tellico `.tc`: zip с `tellico.xml`, обложками и фото.
 */
@RunWith(RobolectricTestRunner::class)
class TellicoTcImportTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var useCase: ImportTellicoZipUseCase
    private lateinit var outputDir: File
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), FilePhotoStorage(ApplicationProvider.getApplicationContext()))
        val bookRepository = BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, photoRepository)
        useCase = ImportTellicoZipUseCase(
            importUseCase = ImportTellicoUseCase(
                csvReader = TellicoCsvReader(),
                mapper = TellicoBookMapper(),
                    movieMapper = TellicoMovieMapper(),
                applier = SnapshotApplier(
    database = db,
    coverStorage = NoOpCoverStorage,
                    bookRepository = bookRepository,
                movieRepository = MovieRepository(db.movieDao(), NoOpCoverStorage, photoRepository),
                    noteRepository = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
                    sessionRepository = SessionRepository(db.readingSessionDao()),
                    tagRepository = TagRepository(db.tagDao()),
                    photoRepository = photoRepository,
                mediaDateRepository = MediaDateRepository(db.mediaDateDao()),
                    photoStorage = FilePhotoStorage(ApplicationProvider.getApplicationContext()),
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
        db.close()
        tempDir.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun importsBookWithNotesSessionsTagsPhotoAndCover() = runTest {
        val result = useCase(buildTc(), ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)

        val book = db.bookDao().observeAll().first().single()
        assertThat(book.title).isEqualTo("Дюна")
        assertThat(book.author).isEqualTo("Фрэнк Герберт")
        assertThat(book.status).isEqualTo(BookStatus.FINISHED)

        val notes = db.noteDao().observeByMedia(book.id, MediaType.BOOKS).first()
        val quote = notes.single { it.kind == NoteKind.QUOTE }
        assertThat(quote.page).isEqualTo(1)
        assertThat(quote.bodyMd).isEqualTo("цитата")
        val review = notes.single { it.kind == NoteKind.REVIEW }
        assertThat(review.bodyMd).isEqualTo("Отзыв")

        assertThat(db.readingSessionDao().observeByBook(book.id).first().single().startedAt)
            .isEqualTo(1730000000000L)

        assertThat(db.tagDao().tagsOf(book.id).map { it.name }).containsExactly("фэнтези")

        val photo = db.photoDao().observeByMedia(book.id, MediaType.BOOKS).first().single()
        assertThat(photo.noteId).isEqualTo(quote.id)
        assertThat(File(photo.path).exists()).isTrue()

        assertThat(book.extraFields["чтец"]?.value).isEqualTo("Иванова")

        val cover = File(book.coverPath!!)
        assertThat(cover.exists()).isTrue()
        assertThat(cover.parentFile).isEqualTo(File(outputDir, "covers"))
    }

    @Test
    fun archiveWithoutImagesWarnsAboutMissingCovers() = runTest {
        // Архив, писанный самим Tellico: картинки лежат рядом с файлом коллекции
        // (в папке `<имя>_files`), а не внутри архива — молчать об этом нельзя.
        val file = File(tempDir, "books.tc")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.writeEntry("tellico.xml", TELLICO_XML.toByteArray())
        }

        val result = useCase(file, ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.coversCopied).isEqualTo(0)
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single()).contains("рядом")
    }

    @Test
    fun archiveWithImagesHasNoWarnings() = runTest {
        val result = useCase(buildTc(), ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.coversCopied).isEqualTo(1)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun importsBareXmlFile() = runTest {
        // Файл не архив: имя может быть любым, формап определён по содержимому.
        val file = File(tempDir, "import-123.zip").apply { writeText(TELLICO_XML) }

        val result = useCase(file, ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)
        assertThat(db.bookDao().observeAll().first().single().title).isEqualTo("Дюна")
    }

    @Test
    fun importsBareXmlFileWithCoverFromItsFolder() = runTest {
        // «Голый» XML: обложки лежат рядом с ним, корень ассетов — его каталог.
        File(tempDir, "images").mkdirs()
        File(tempDir, "images/$COVER_NAME").writeBytes(pngBytes())
        val file = File(tempDir, "import-123.zip").apply { writeText(TELLICO_XML) }

        val result = useCase(file, ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.imported).isEqualTo(1)
        val cover = File(db.bookDao().observeAll().first().single().coverPath!!)
        assertThat(cover.exists()).isTrue()
        assertThat(cover.parentFile).isEqualTo(File(outputDir, "covers"))
    }

    @Test
    fun missingFileIsReportedAsError() = runTest {
        val result = useCase(File(tempDir, "нет-такого.tc"), ImportFormat.TELLICO_XML, outputDir)

        assertThat(result.total).isEqualTo(0)
        assertThat(result.isSuccess).isFalse()
        assertThat(result.error).isNotNull()
    }

    @Test
    fun importsBareCsvFile() = runTest {
        val file = File(tempDir, "import-456.zip").apply {
            writeText("Заголовок;Автор\nДюна;Фрэнк Герберт\n")
        }

        val result = useCase(file, ImportFormat.TELLICO_CSV, outputDir)

        assertThat(result.imported).isEqualTo(1)
        val book = db.bookDao().observeAll().first().single()
        assertThat(book.title).isEqualTo("Дюна")
        assertThat(book.author).isEqualTo("Фрэнк Герберт")
    }

    private fun buildTc(): File {
        val file = File(tempDir, "books.tc")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.writeEntry("tellico.xml", TELLICO_XML.toByteArray())
            zip.writeEntry("images/$COVER_NAME", pngBytes())
            zip.writeEntry("photos/$PHOTO_NAME", pngBytes())
        }
        return file
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
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

    private companion object {
        private const val COVER_NAME = "0dcebf7e7711bd7154db82b21cac7f61.webp"
        private const val PHOTO_NAME = "5289df737df57326fcdd22597afb1fac.jpg"

        private val TELLICO_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://www.tellico.org/" date="2024-01-01">
              <collection type="2">
                <fields>
                  <field name="title" title="Заголовок" type="1"/>
                  <field name="author" title="Автор" type="1"/>
                  <field name="read" title="Прочитано" type="4"/>
                  <field name="cover" title="Лицевая сторона обложки" type="10"/>
                  <field name="цитаты" title="Цитаты" type="2">
                    <prop name="columns">2</prop>
                  </field>
                  <field name="рецензия" title="Рецензия" type="1"/>
                  <field name="теги" title="Теги" type="1"/>
                  <field name="сессии-чтения" title="Сессии чтения" type="2">
                    <prop name="columns">6</prop>
                  </field>
                  <field name="фото-заметок" title="Фото заметок" type="2">
                    <prop name="columns">3</prop>
                  </field>
                  <field name="чтец" title="Чтец" type="1"/>
                </fields>
                <entry id="1">
                  <title>Дюна</title>
                  <authors>
                    <author>Фрэнк Герберт</author>
                  </authors>
                  <read>true</read>
                  <cover>$COVER_NAME</cover>
                  <цитатыs>
                    <цитаты>1::цитата</цитаты>
                  </цитатыs>
                  <рецензия>Отзыв</рецензия>
                  <тегиs>
                    <теги>фэнтези</теги>
                  </тегиs>
                  <сессии-чтенияs>
                    <сессии-чтения>1730000000000::1730000600000::1::20::600::читал</сессии-чтения>
                  </сессии-чтенияs>
                  <фото-заметокs>
                    <фото-заметок>photos/$PHOTO_NAME::1::1730000000000</фото-заметок>
                  </фото-заметокs>
                  <чтец>Иванова</чтец>
                </entry>
              </collection>
            </tellico>
        """.trimIndent()
    }
}
