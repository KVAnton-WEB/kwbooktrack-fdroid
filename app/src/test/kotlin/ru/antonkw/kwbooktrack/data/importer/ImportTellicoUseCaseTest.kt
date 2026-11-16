package ru.antonkw.kwbooktrack.data.importer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
import ru.antonkw.kwbooktrack.data.tellico.TellicoFields
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import java.io.File
import java.nio.file.Files
import ru.antonkw.kwbooktrack.data.repository.MovieRepository

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
            movieMapper = TellicoMovieMapper(),
            applier = applierFor(bookRepository, photoRepository),
        )
        // Тот же импорт, но книга «отменяется» на чтении.
        cancellingUseCase = ImportTellicoUseCase(
            csvReader = TellicoCsvReader(),
            mapper = TellicoBookMapper(),
            movieMapper = TellicoMovieMapper(),
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
        // Частичная потеря — своё сообщение: «обложки не найдены» при половине
        // перенесённых обложек вводит в заблуждение.
        assertThat(result.warnings.single()).contains("1 из 2")
        assertThat(result.warnings.single()).doesNotContain("не найдены")
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

    @Test
    fun importsMoviesFromMovieCollection() = runTest {
        val result = useCase(
            listOf(movieRecord("Малхолланд Драйв")),
            ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.movies).isEqualTo(1)
        val movies = db.movieDao().observeAll().first()
        assertThat(movies).hasSize(1)
        assertThat(movies.single().title).isEqualTo("Малхолланд Драйв")
        // Запись фильма не должна попасть в книги.
        assertThat(db.bookDao().observeAll().first()).isEmpty()
    }

    @Test
    fun countsBooksAndMoviesSeparately() = runTest {
        val result = useCase(
            listOf(recordWithCover(), movieRecord("Малхолланд Драйв")),
            ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
        )

        assertThat(result.imported).isEqualTo(2)
        assertThat(result.movies).isEqualTo(1)
    }

    @Test
    fun skippedMovieDoesNotBreakBookImport() = runTest {
        // Фильм без названия пропускается, но книга из того же файла
        // импортируется: одна плохая запись не должна ронять импорт.
        val result = useCase(
            listOf(movieRecord(""), recordWithCover()),
            ImportTellicoOptions(assetRoot = staging, coverOutputDir = outputDir),
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.movies).isEqualTo(0)
        assertThat(db.bookDao().observeAll().first()).hasSize(1)
    }

    private fun movieRecord(title: String): TellicoRecord =
        TellicoRecord(
            fields = mapOf(
                "title" to title,
                "year" to "1999",
                "running-time" to "147",
            ),
            collectionType = TellicoFields.COLLECTION_MOVIES,
        )

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
                movieRepository = MovieRepository(db.movieDao(), NoOpCoverStorage, photoRepository),
        noteRepository = NoteRepository(db.noteDao(), NoOpPhotoStorage),
        sessionRepository = SessionRepository(db.readingSessionDao()),
        tagRepository = TagRepository(db.tagDao()),
        photoRepository = photoRepository,
        photoStorage = NoOpPhotoStorage,
        transcoder = CoverTranscoder(),
    )
}
