package ru.antonkw.kwbooktrack.data.importer

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

    private val tellicoCsv: File = File("/home/kvanton/projects/pet/kwbooktrack/tellico/tellico.csv")
    private val coversDir: File = File("/home/kvanton/projects/pet/kwbooktrack/tellico/Books_files")

    @Before
    fun setUp() {
        assumeTrue("real tellico.csv present", tellicoCsv.exists() && tellicoCsv.length() > 0)
        assumeTrue("Books_files present", coversDir.exists() && coversDir.isDirectory)

        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        noteRepo = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage)
        useCase = ImportTellicoUseCase(
            csvReader = TellicoCsvReader(),
            mapper = TellicoBookMapper(),
            bookRepository = repo,
            noteRepository = noteRepo,
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
