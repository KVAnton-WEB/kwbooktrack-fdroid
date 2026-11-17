package ru.antonkw.kwbooktrack.data.exporter

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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.importer.ImportKwBooktrackZipUseCase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.storage.FilePhotoStorage
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * Свой zip-бэкап: дополнительные даты просмотра должны пережить
 * круговой импорт.
 *
 * В Tellico уходит одна дата — самая ранняя; в своём бэкапе — все.
 */
@RunWith(RobolectricTestRunner::class)
class KwBooktrackZipDatesTest {

    private lateinit var sourceDb: KwBooktrackDatabase
    private lateinit var targetDb: KwBooktrackDatabase
    private lateinit var exportUseCase: ExportZipUseCase
    private lateinit var importUseCase: ImportKwBooktrackZipUseCase
    private lateinit var tempDir: File
    private lateinit var outputDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        sourceDb = TestDb.createInMemory(context)
        targetDb = TestDb.createInMemory(context)
        val sourcePhotos = PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage)
        val sourceMovies = MovieRepository(sourceDb.movieDao(), NoOpCoverStorage, sourcePhotos)
        exportUseCase = ExportZipUseCase(
            snapshotBuilder = SnapshotBuilder(
                bookRepository = BookRepository(
                    sourceDb.bookDao(),
                    sourceDb.tagDao(),
                    NoOpCoverStorage,
                    sourcePhotos,
                ),
                movieRepository = sourceMovies,
                noteDao = sourceDb.noteDao(),
                sessionDao = sourceDb.readingSessionDao(),
                photoDao = sourceDb.photoDao(),
                tagDao = sourceDb.tagDao(),
                mediaDateDao = sourceDb.mediaDateDao(),
            ),
            versionProvider = object : AppVersionProvider {
                override val appVersion: String = "0.1.0"
            },
            database = sourceDb,
        )
        val targetPhotos = PhotoRepository(targetDb.photoDao(), FilePhotoStorage(context))
        importUseCase = ImportKwBooktrackZipUseCase(
            SnapshotApplier(
                bookRepository = BookRepository(
                    targetDb.bookDao(),
                    targetDb.tagDao(),
                    NoOpCoverStorage,
                    targetPhotos,
                ),
                movieRepository = MovieRepository(targetDb.movieDao(), NoOpCoverStorage, targetPhotos),
                noteRepository = NoteRepository(targetDb.noteDao(), FilePhotoStorage(context)),
                sessionRepository = SessionRepository(targetDb.readingSessionDao()),
                tagRepository = TagRepository(targetDb.tagDao()),
                photoRepository = targetPhotos,
                mediaDateRepository = MediaDateRepository(targetDb.mediaDateDao()),
                photoStorage = FilePhotoStorage(context),
                transcoder = CoverTranscoder(),
            ),
        )
        tempDir = Files.createTempDirectory("kw-zip-dates-").toFile()
        outputDir = Files.createTempDirectory("kw-zip-dates-out-").toFile()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        tempDir.deleteRecursively()
        outputDir.deleteRecursively()
    }

    @Test
    fun importKeepsDatesAlreadyInTheLibrary(): Unit = runBlocking {
        // В библиотеке фильм пересмотрен: март и май. Импортируем архив,
        // сделанный до пересмотра (одна дата, март) — обе даты должны уцелеть,
        // а основной остаться самой ранней.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val targetPhotos = PhotoRepository(targetDb.photoDao(), FilePhotoStorage(context))
        val targetMovies = MovieRepository(targetDb.movieDao(), NoOpCoverStorage, targetPhotos)
        val targetDates = MediaDateRepository(targetDb.mediaDateDao())

        val march = LocalDate.of(2024, 3, 10).toEpochMs()
        val may = LocalDate.of(2024, 5, 15).toEpochMs()
        val sourceId = MovieRepository(
            sourceDb.movieDao(),
            NoOpCoverStorage,
            PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage),
        ).insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                year = 1999,
                durationMin = 120,
                // В архиве даты ещё нет: архив сделан до первого просмотра.
                watchedAt = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val targetId = targetMovies.insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                year = 1999,
                durationMin = 120,
                watchedAt = march,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        targetDates.replaceFor(targetId, MediaType.MOVIES, listOf(may))

        val file = File(tempDir, "backup.zip")
        exportUseCase(file)
        importUseCase(file, outputDir)

        val restored = targetMovies.findById(targetId)!!
        assertThat(restored.watchedAt).isEqualTo(march)
        assertThat(targetDates.getFor(targetId, MediaType.MOVIES)).containsExactly(may)
        assertThat(sourceId).isGreaterThan(0L)
    }

    @Test
    fun allWatchDatesSurviveTheRoundTrip(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sourcePhotos = PhotoRepository(sourceDb.photoDao(), NoOpPhotoStorage)
        val movies = MovieRepository(sourceDb.movieDao(), NoOpCoverStorage, sourcePhotos)
        val sourceDates = MediaDateRepository(sourceDb.mediaDateDao())

        val march = LocalDate.of(2024, 3, 10).toEpochMs()
        val may = LocalDate.of(2024, 5, 15).toEpochMs()
        val id = movies.insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                durationMin = 120,
                watchedAt = march,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        sourceDates.replaceFor(id, MediaType.MOVIES, listOf(may))

        val file = File(tempDir, "backup.zip")
        exportUseCase(file)
        importUseCase(file, outputDir)

        val restored = targetDb.movieDao().observeAll().first().single()
        // Основная дата — самая ранняя.
        assertThat(restored.watchedAt).isEqualTo(march)
        // Вторая дата — в дополнительных.
        assertThat(MediaDateRepository(targetDb.mediaDateDao()).getFor(restored.id, MediaType.MOVIES))
            .containsExactly(may)
    }
}
