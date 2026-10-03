package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import java.io.File
import java.io.FileReader
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class TellicoImportIntegrationTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: BookRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var useCase: ImportTellicoUseCase
    private lateinit var tempOut: File
    private lateinit var tellicoCsv: File
    private lateinit var coversDir: File

    @Before
    fun setUp() {
        // Resolve the bundled CSV from test resources (no absolute path) and the
        // optional covers directory from an environment variable. The covers
        // directory is ~50MB of real Tellico art and isn't worth checking in.
        val resource = javaClass.classLoader!!.getResource("fixtures/tellico/sample.csv")
            ?: error("fixtures/tellico/sample.csv not found in test resources")
        tellicoCsv = File(resource.toURI())
        assumeTrue("sample.csv present", tellicoCsv.exists() && tellicoCsv.length() > 0)

        val envCovers = System.getenv("KWBOOKTRACK_TELLICO_COVERS")
        coversDir = if (envCovers != null) File(envCovers) else File("tellico/Books_files")
        val coversAvailable = coversDir.exists() && coversDir.isDirectory

        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = BookRepository(
            db.bookDao(),
            db.tagDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
        noteRepo = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage)
        useCase = ImportTellicoUseCase(
            csvReader = TellicoCsvReader(),
            mapper = TellicoBookMapper(),
            bookRepository = repo,
            noteRepository = noteRepo,
            transcoder = CoverTranscoder(),
        )
        tempOut = Files.createTempDirectory("kwbooktrack-test-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempOut.deleteRecursively()
    }

    @Test
    fun dryRunReportsWithoutWriting() = runTest {
        val result = FileReader(tellicoCsv).use { reader ->
            useCase(
                input = reader,
                options = ImportTellicoOptions(dryRun = true),
            )
        }

        assertThat(result.total).isGreaterThan(600)
        assertThat(result.imported).isGreaterThan(600)
        assertThat(result.dryRun).isTrue()
        assertThat(result.coversCopied).isEqualTo(0)
        assertThat(repo.count()).isEqualTo(0)
    }

    @Test
    fun fullImportInsertsBooksAndCopiesCovers() = runBlocking {
        // The bundled sample.csv has no associated covers dir; skip cover assertions
        // when covers aren't available (env var not set).
        assumeTrue("covers dir present (set KWBOOKTRACK_TELLICO_COVERS to enable)", coversDir.exists() && coversDir.isDirectory)

        val result = FileReader(tellicoCsv).use { reader ->
            useCase(
                input = reader,
                options = ImportTellicoOptions(
                    coverSourceDir = coversDir,
                    coverOutputDir = tempOut,
                ),
            )
        }

        assertThat(result.total).isGreaterThan(600)
        assertThat(result.imported).isGreaterThan(600)
        assertThat(result.coversCopied).isGreaterThan(0)
        assertThat(repo.count()).isAtMost(result.imported)
        assertThat(repo.count()).isAtLeast(result.imported / 2)

        val copiedCovers = File(tempOut, "covers").listFiles().orEmpty().size
        // Discrepancy between reported copies and unique files can occur when the source CSV
        // contains duplicate rows for the same book: each duplicate increments coversCopied,
        // but they all write to the same {bookId}.{ext} destination.
        assertThat(copiedCovers.toLong()).isAtLeast(result.coversCopied.toLong() * 9 / 10)
    }

    @Test
    fun secondImportIsIdempotent() = runBlocking {
        assumeTrue("covers dir present (set KWBOOKTRACK_TELLICO_COVERS to enable)", coversDir.exists() && coversDir.isDirectory)

        FileReader(tellicoCsv).use { reader ->
            useCase(
                input = reader,
                options = ImportTellicoOptions(coverSourceDir = coversDir, coverOutputDir = tempOut),
            )
        }
        val countAfterFirst = repo.count()

        FileReader(tellicoCsv).use { reader ->
            useCase(
                input = reader,
                options = ImportTellicoOptions(coverSourceDir = coversDir, coverOutputDir = tempOut),
            )
        }

        assertThat(repo.count()).isEqualTo(countAfterFirst)
    }

}
