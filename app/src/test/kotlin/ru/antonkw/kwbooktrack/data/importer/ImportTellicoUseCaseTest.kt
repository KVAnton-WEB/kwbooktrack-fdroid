package ru.antonkw.kwbooktrack.data.importer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import java.io.File
import java.nio.file.Files

/**
 * Импорт записей Tellico: предупреждения о потерянных обложках и отмена
 * импорта (отменённый импорт не должен выглядеть успешным).
 */
@RunWith(RobolectricTestRunner::class)
class ImportTellicoUseCaseTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var useCase: ImportTellicoUseCase
    private lateinit var cancellingUseCase: ImportTellicoUseCase
    private lateinit var staging: File
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        val bookRepository = BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, photoRepository)
        useCase = ImportTellicoUseCase(
            csvReader = TellicoCsvReader(),
            mapper = TellicoBookMapper(),
            applier = applierFor(bookRepository, photoRepository),
        )
        // Тот же импорт, но книга «отменяется» на чтении.
        cancellingUseCase = ImportTellicoUseCase(
            csvReader = TellicoCsvReader(),
            mapper = TellicoBookMapper(),
            applier = applierFor(
                BookRepository(
                    CancellingBookDao(db.bookDao()),
                    db.tagDao(),
                    NoOpCoverStorage,
                    photoRepository,
                ),
                photoRepository,
            ),
        )
        staging = Files.createTempDirectory("kwbooktrack-import-").toFile()
        outputDir = Files.createTempDirectory("kwbooktrack-import-out-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        staging.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun coversReferencedButMissingAreReportedAsWarning() = runTest {
        // Tellico хранит картинки рядом с файлом коллекции — в архиве их нет.
        val result = useCase(
            listOf(recordWithCover()),
            ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.coversCopied).isEqualTo(0)
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single()).contains("рядом")
    }

    @Test
    fun partiallyCopiedCoversAreReportedAsWarning() = runTest {
        // Обложка одной книги нашлась, другой — нет: молчать о потере нельзя.
        File(staging, "abc.webp").writeBytes(byteArrayOf(1, 2, 3))

        val result = useCase(
            listOf(recordWithCover("abc.webp"), recordWithCover("missing.webp", "Дюна 2")),
            ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
        )

        assertThat(result.coversCopied).isEqualTo(1)
        assertThat(result.warnings).hasSize(1)
    }

    @Test
    fun importedCoversProduceNoWarning() = runTest {
        File(staging, "abc.webp").writeBytes(byteArrayOf(1, 2, 3))

        val result = useCase(
            listOf(recordWithCover()),
            ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
        )

        assertThat(result.coversCopied).isEqualTo(1)
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun cancellationIsNotSwallowedAsRecordError() = runTest {
        // Отменённый импорт должен отмениться, а не отчитаться «успехом».
        File(staging, "abc.webp").writeBytes(byteArrayOf(1, 2, 3))

        val error = runCatching {
            cancellingUseCase(
                listOf(recordWithCover()),
                ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    private fun recordWithCover(cover: String = "abc.webp", title: String = "Дюна"): TellicoRecord =
        TellicoRecord(
            fields = mapOf(
                "title" to title,
                "author" to "Фрэнк Герберт",
                "cover" to cover,
            ),
        )

    private fun applierFor(
        bookRepository: BookRepository,
        photoRepository: PhotoRepository,
    ) = SnapshotApplier(
        bookRepository = bookRepository,
        noteRepository = NoteRepository(db.noteDao(), NoOpPhotoStorage),
        sessionRepository = SessionRepository(db.readingSessionDao()),
        tagRepository = TagRepository(db.tagDao()),
        photoRepository = photoRepository,
        photoStorage = NoOpPhotoStorage,
        transcoder = CoverTranscoder(),
    )
}
