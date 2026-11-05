package ru.antonkw.kwbooktrack.data.exporter

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class ExportZipUseCaseTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: BookRepository
    private lateinit var useCase: ExportZipUseCase
    private lateinit var tempDir: File

    private val versionProvider = object : AppVersionProvider {
        override val appVersion: String = "0.1.0"
    }

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = BookRepository(
            db.bookDao(),
            db.tagDao(),
            NoOpCoverStorage,
            PhotoRepository(db.photoDao(), NoOpPhotoStorage),
        )
        useCase = ExportZipUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = repo,
                noteDao = db.noteDao(),
                sessionDao = db.readingSessionDao(),
                photoDao = db.photoDao(),
                tagDao = db.tagDao(),
            ),
            versionProvider = versionProvider,
            database = db,
        )
        tempDir = Files.createTempDirectory("kwbooktrack-export-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun exportRoundTripsBackToBookList() {
        runBlocking {
            repo.insert(sampleBook("Властелин Колец", "Толкин", pageCount = 1200, status = BookStatus.FINISHED))
            repo.insert(sampleBook("Солярис", "Лем", pageCount = 320, status = BookStatus.READING))

            val outFile = File(tempDir, "export.zip")
            val manifest = useCase(outFile)
            assertThat(outFile.exists()).isTrue()
            assertThat(manifest.counts.books).isEqualTo(2)

            val entries = readZipEntries(outFile)
            assertThat(entries.keys).contains("manifest.json")
            assertThat(entries.keys).contains("books.csv")

            val manifestJson = entries["manifest.json"]!!
            val parsed = Json.decodeFromString(ExportManifest.serializer(), manifestJson)
            assertThat(parsed.counts.books).isEqualTo(2)
            assertThat(parsed.source).isEqualTo("kwbooktrack")

            val csv = entries["books.csv"]!!
            val format = CSVFormat.Builder.create(CSVFormat.EXCEL).setDelimiter(';').setHeader().setSkipHeaderRecord(true).build()
            val records = CSVParser(StringReader(csv), format).use { it.records }
            assertThat(records).hasSize(2)
            val titles = records.map { it.get("title") }.toSet()
            assertThat(titles).containsExactly("Властелин Колец", "Солярис")
        }
    }

    @Test
    fun exportOmitsMissingCovers() {
        runBlocking {
            repo.insert(sampleBook("Book With Cover", "Author", coverPath = "/nonexistent/cover.jpg"))

            val outFile = File(tempDir, "export.zip")
            useCase(outFile)

            val entries = readZipEntries(outFile)
            assertThat(entries.keys).contains("books.csv")
            assertThat(entries.keys.none { it.startsWith("covers/") }).isTrue()
        }
    }

    @Test
    fun exportIncludesExistingCover() {
        runBlocking {
            val coverFile = File(tempDir, "cover.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
            repo.insert(sampleBook("Book With Cover", "Author", coverPath = coverFile.absolutePath))

            val outFile = File(tempDir, "export.zip")
            useCase(outFile)

            val entries = readZipEntries(outFile)
            val coverEntry = entries.keys.firstOrNull { it.startsWith("covers/") }
            assertThat(coverEntry).isNotNull()
        }
    }

    @Test
    fun emptyLibraryExportsHeaderOnly() {
        runBlocking {
            val outFile = File(tempDir, "export.zip")
            val manifest = useCase(outFile)

            assertThat(manifest.counts.books).isEqualTo(0)
            val csv = readZipEntries(outFile)["books.csv"]!!
            assertThat(csv).startsWith("title;")
        }
    }

    @Test
    fun fileVanishedBeforeWriteIsSkippedWithoutBreakingExport() {
        runBlocking {
            val cover = File(tempDir, "cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            repo.insert(sampleBook("Book With Cover", "Author", coverPath = cover.absolutePath))
            // Версия провайдера читается между подготовкой архива и его записью —
            // «исчезаем» с файлом обложки именно тут.
            val deleting = ExportZipUseCase(
                snapshotBuilder = SnapshotBuilder(
                    bookRepository = repo,
                    noteDao = db.noteDao(),
                    sessionDao = db.readingSessionDao(),
                    photoDao = db.photoDao(),
                    tagDao = db.tagDao(),
                ),
                versionProvider = object : AppVersionProvider {
                    override val appVersion: String
                        get() {
                            cover.delete()
                            return "0.1.0"
                        }
                },
                database = db,
            )

            val outFile = File(tempDir, "export.zip")
            val manifest = deleting(outFile)

            // Файл исчез — экспорт не роняется, обложки в архиве нет.
            assertThat(manifest.counts.books).isEqualTo(1)
            assertThat(outFile.exists()).isTrue()
            val entries = readZipEntries(outFile)
            assertThat(entries.keys).contains("books.csv")
            assertThat(entries.keys.none { it.startsWith("covers/") }).isTrue()
        }
    }

    private fun readZipEntries(zipFile: File): Map<String, String> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                out[e.name] = zin.readBytes()
                e = zin.nextEntry
            }
        }
        return out.mapValues { it.value.toString(Charsets.UTF_8) }
    }

    private fun sampleBook(
        title: String,
        author: String,
        pageCount: Int? = null,
        status: BookStatus = BookStatus.WISHLIST,
        coverPath: String? = null,
    ) = BookEntity(
        title = title,
        author = author,
        pageCount = pageCount,
        status = status,
        coverPath = coverPath,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
