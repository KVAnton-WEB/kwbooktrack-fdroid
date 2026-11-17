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
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import ru.antonkw.kwbooktrack.data.repository.MovieRepository

@RunWith(RobolectricTestRunner::class)
class ExportZipUseCaseTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: BookRepository
    private lateinit var movieRepo: MovieRepository
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
        movieRepo = MovieRepository(
            db.movieDao(),
            NoOpCoverStorage,
            PhotoRepository(db.photoDao(), NoOpPhotoStorage),
        )
        useCase = ExportZipUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = repo,
                movieRepository = movieRepo,
                noteDao = db.noteDao(),
                sessionDao = db.readingSessionDao(),
                photoDao = db.photoDao(),
                tagDao = db.tagDao(),
                mediaDateDao = db.mediaDateDao(),
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
    fun exportQuotesFieldWithLoneCarriageReturn() {
        // Одиночный CR — тоже конец записи для CSV. Без кавычек строка рвалась
        // на импорте, и следующая строка читалась как отдельная книга.
        runBlocking {
            repo.insert(sampleBook("Дюна", "Герберт", annotation = "первая\rвторая"))

            val outFile = File(tempDir, "export.zip")
            useCase(outFile)

            val csv = readZipEntries(outFile)["books.csv"]!!
            val format = CSVFormat.Builder.create(CSVFormat.EXCEL).setDelimiter(';').setHeader().setSkipHeaderRecord(true).build()
            val records = CSVParser(StringReader(csv), format).use { it.records }
            assertThat(records).hasSize(1)
            assertThat(records.single().get("title")).isEqualTo("Дюна")
            assertThat(records.single().get("annotation")).isEqualTo("первая\rвторая")
        }
    }

    @Test
    fun exportCountsLostCovers() {
        // Обложка, которой нет на диске, — потеря, о которой должен быть виден
        // счётчик: раньше в архиве просто не было файла, и заметки не было.
        runBlocking {
            repo.insert(sampleBook("Book With Cover", "Author", coverPath = "/nonexistent/cover.jpg"))

            val outFile = File(tempDir, "export.zip")
            val manifest = useCase(outFile)

            assertThat(manifest.counts.coversMissing).isEqualTo(1)
            assertThat(manifest.counts.covers).isEqualTo(0)
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
    fun csvStoresOnlyCoverFileName() {
        runBlocking {
            val coverFile = File(tempDir, "cover.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
            repo.insert(sampleBook("Book With Cover", "Author", coverPath = coverFile.absolutePath))

            val outFile = File(tempDir, "export.zip")
            useCase(outFile)

            // Внутренний путь приложения в бэкапе бессмысленен: импорт ищет
            // обложку по имени файла.
            val csv = readZipEntries(outFile)["books.csv"]!!
            val header = csv.lineSequence().first().split(";")
            val row = csv.lineSequence().drop(1).first().split(";")
            assertThat(row[header.indexOf("cover_path")]).isEqualTo("cover.jpg")
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
                    movieRepository = movieRepo,
                    noteDao = db.noteDao(),
                    sessionDao = db.readingSessionDao(),
                    photoDao = db.photoDao(),
                    tagDao = db.tagDao(),
                mediaDateDao = db.mediaDateDao(),
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

    @Test
    fun exportWritesMoviesCsv() {
        runBlocking {
            movieRepo.insert(sampleMovie("Дюна", 2021))
            movieRepo.insert(sampleMovie("Интерстеллар", 2014))

            val outFile = File(tempDir, "export.zip")
            useCase(outFile)

            val entries = readZipEntries(outFile)
            assertThat(entries.keys).contains("movies.csv")
            val csv = entries["movies.csv"]!!
            // Заголовок `movies.csv` — ровно эти колонки и в этом порядке.
            assertThat(csv.lineSequence().first()).isEqualTo(MOVIES_HEADER)
            val format = CSVFormat.Builder.create(CSVFormat.EXCEL).setDelimiter(';').setHeader().setSkipHeaderRecord(true).build()
            val records = CSVParser(StringReader(csv), format).use { it.records }
            assertThat(records).hasSize(2)
            assertThat(records.map { it.get("title") }.toSet()).containsExactly("Дюна", "Интерстеллар")
        }
    }

    @Test
    fun exportCountsIncludeMovies() {
        runBlocking {
            val movieId = movieRepo.insert(sampleMovie("Дюна", 2021))
            movieRepo.insert(sampleMovie("Интерстеллар", 2014))
            // Заметка фильма попадает в архив — значит, и в счётчик.
            NoteRepository(db.noteDao(), NoOpPhotoStorage).insert(
                NoteEntity(
                    mediaId = movieId,
                    mediaType = MediaType.MOVIES,
                    bodyMd = "Заметка",
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
            )

            val outFile = File(tempDir, "export.zip")
            useCase(outFile)

            val manifestJson = readZipEntries(outFile)["manifest.json"]!!
            assertThat(manifestJson).contains("\"movies\":2")
            val parsed = Json.decodeFromString(ExportManifest.serializer(), manifestJson)
            assertThat(parsed.counts.movies).isEqualTo(2)
            assertThat(parsed.counts.notes).isEqualTo(1)
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
        annotation: String? = null,
    ) = BookEntity(
        title = title,
        author = author,
        pageCount = pageCount,
        status = status,
        coverPath = coverPath,
        annotation = annotation,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun sampleMovie(title: String, year: Int) = MovieEntity(
        title = title,
        year = year,
        source = MediaSource.MANUAL,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private companion object {
        /**
         * Заголовок `movies.csv` — как в плане: ровно эти колонки и в этом
         * порядке. Литерал, а не константа экспортёра: иначе тест повторял бы
         * сам себя и не заметил бы изменения формата.
         */
        const val MOVIES_HEADER =
            "title;original_title;year;genre;nationality;director;cast;duration_min;rating;watched_at;" +
                "cover_path;plot;comments;url;is_downloaded;source;id;created_at;updated_at"
    }
}
