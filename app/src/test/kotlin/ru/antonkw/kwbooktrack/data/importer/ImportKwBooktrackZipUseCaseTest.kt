package ru.antonkw.kwbooktrack.data.importer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.exporter.ExportZipUseCase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class ImportKwBooktrackZipUseCaseTest {
    private lateinit var sourceDb: KwBooktrackDatabase
    private lateinit var sourceRepo: BookRepository
    private lateinit var export: ExportZipUseCase

    private lateinit var targetDb: KwBooktrackDatabase
    private lateinit var targetRepo: BookRepository
    private lateinit var targetNoteRepo: NoteRepository
    private lateinit var importer: ImportKwBooktrackZipUseCase

    private lateinit var tempDir: File

    private val versionProvider = object : AppVersionProvider {
        override val appVersion: String = "0.1.0-test"
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("kwbooktrack-roundtrip-").toFile()

        sourceDb = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        sourceRepo = BookRepository(
            bookDao = sourceDb.bookDao(),
            tagDao = sourceDb.tagDao(),
            coverStorage = NoOpCoverStorage,
            photoRepository = PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage),
        )
        export = ExportZipUseCase(
            bookRepository = sourceRepo,
            noteDao = sourceDb.noteDao(),
            sessionDao = sourceDb.readingSessionDao(),
            photoDao = sourceDb.photoDao(),
            tagDao = sourceDb.tagDao(),
            versionProvider = versionProvider,
            database = sourceDb,
        )

        targetDb = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        targetRepo = BookRepository(
            bookDao = targetDb.bookDao(),
            tagDao = targetDb.tagDao(),
            coverStorage = NoOpCoverStorage,
            photoRepository = PhotoRepository(targetDb.photoDao(), NoOpPhotoStorage),
        )
        targetNoteRepo = NoteRepository(
            noteDao = targetDb.noteDao(),
            photoStorage = NoOpPhotoStorage,
        )
        importer = ImportKwBooktrackZipUseCase(targetRepo, NoOpCoverStorage)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        tempDir.deleteRecursively()
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

            val result = importer(outFile)
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
            val result = importer(fake)
            assertThat(result.isSuccess).isFalse()
            assertThat(result.error?.message).contains("kwbooktrack")
        }
    }

    @Test
    fun import_emptyCsvReportsZero() {
        runBlocking {
            val outFile = File(tempDir, "empty.zip")
            export(outFile)
            val result = importer(outFile)
            assertThat(result.isSuccess).isTrue()
            assertThat(result.imported).isEqualTo(0)
        }
    }
}