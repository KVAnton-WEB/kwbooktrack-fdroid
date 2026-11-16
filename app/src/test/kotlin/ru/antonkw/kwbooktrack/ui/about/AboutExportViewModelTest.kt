package ru.antonkw.kwbooktrack.ui.about

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.TestViewModels
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.exporter.ExportTellicoTcUseCase
import ru.antonkw.kwbooktrack.data.exporter.ExportZipUseCase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import ru.antonkw.kwbooktrack.data.repository.MovieRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AboutExportViewModelTest {
    private lateinit var context: Context
    private lateinit var db: KwBooktrackDatabase
    private lateinit var useCase: ExportZipUseCase
    private lateinit var vm: AboutExportViewModel
    private val dispatcher = StandardTestDispatcher()

    private val versionProvider = object : AppVersionProvider {
        override val appVersion: String = "0.4.0-test"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = TestDb.createInMemory(context)
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        val bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            NoOpCoverStorage,
            photoRepository,
        )
        val snapshotBuilder = SnapshotBuilder(
            bookRepository = bookRepository,
            movieRepository = MovieRepository(db.movieDao(), NoOpCoverStorage, PhotoRepository(db.photoDao(), NoOpPhotoStorage)),
            noteDao = db.noteDao(),
            sessionDao = db.readingSessionDao(),
            photoDao = db.photoDao(),
            tagDao = db.tagDao(),
        )
        useCase = ExportZipUseCase(
            snapshotBuilder = snapshotBuilder,
            versionProvider = versionProvider,
            database = db,
        )
        vm = AboutExportViewModel(
            context = context,
            exportZipUseCase = useCase,
            exportTellicoUseCase = ExportTellicoTcUseCase(
                snapshotBuilder = snapshotBuilder,
                database = db,
                versionProvider = versionProvider,
            ),
        )
        TestViewModels.track(vm)
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun suggestedFilenameIncludesTimestampAndExtension() {
        val name = AboutExportViewModel.suggestedFilename()
        assertThat(name).startsWith("kwbooktrack-backup-")
        assertThat(name).endsWith(".zip")
    }

    @Test
    fun suggestedFilenameIncludesTimestampPattern() {
        // yyMMdd_HH-mm-ss ≈ 19 chars between "kwbooktrack-backup-" and ".zip".
        // Lock the format down so a careless rename doesn't silently break
        // downstream tools that grep on the date suffix.
        val name = AboutExportViewModel.suggestedFilename()
        val ts = name.removePrefix("kwbooktrack-backup-").removeSuffix(".zip")
        assertThat(ts).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}")
    }

    @Test
    fun suggestedTellicoFilenameIncludesTimestampAndExtension() {
        val name = AboutExportViewModel.suggestedTellicoFilename()
        assertThat(name).startsWith("kwbooktrack-tellico-")
        assertThat(name).endsWith(".tc")
    }

    @Test
    fun suggestedTellicoFilenameIncludesTimestampPattern() {
        val name = AboutExportViewModel.suggestedTellicoFilename()
        val ts = name.removePrefix("kwbooktrack-tellico-").removeSuffix(".tc")
        assertThat(ts).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}")
    }

    @Test
    fun tellicoMimeTypeHasNoExtensionSoSafKeepsTheTcName() {
        // SAF дописывает расширение mime-типа: у `application/zip` это `.zip`, и
        // файл сохранился бы как `....tc.zip` — Tellico такой фильтр не видит.
        assertThat(AboutExportViewModel.TELLICO_MIME).isEqualTo("application/octet-stream")
    }

    @Test
    fun acknowledgeClearsState() {
        // acknowledge() resets the StateFlow to its default; it doesn't require
        // a prior error to be observable.
        vm.acknowledge()
        assertThat(vm.state.value.inProgress).isFalse()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.lastManifest).isNull()
    }

    @Test
    fun initialStateIsIdle() {
        val state = vm.state.value
        assertThat(state.inProgress).isFalse()
        assertThat(state.lastManifest).isNull()
        assertThat(state.error).isNull()
    }

    @Test
    fun exportZipUseCaseWritesNonEmptyZipIntoStream() = runTest(dispatcher) {
        // Direct roundtrip — proves the use case (the part the VM delegates to)
        // produces a valid ZIP regardless of any SAF plumbing.
        ru.antonkw.kwbooktrack.data.db.entity.BookEntity(
            title = "Dune",
            author = "Herbert",
            createdAt = 0L,
            updatedAt = 0L,
        ).let { db.bookDao().insert(it) }

        val sink = java.io.ByteArrayOutputStream()
        val manifest = useCase(sink)
        assertThat(manifest.counts.books).isEqualTo(1)
        assertThat(sink.toByteArray().size).isGreaterThan(0)
        // PK is the ZIP local-file-header magic.
        assertThat(sink.toByteArray().copyOf(2).toString(Charsets.UTF_8)).isEqualTo("PK")
    }

    @Test
    fun exportTellicoToUriWritesArchiveWithTellicoXml() = runTest(dispatcher) {
        db.bookDao().insert(
            BookEntity(title = "Дюна", author = "Фрэнк Герберт", createdAt = 0L, updatedAt = 0L),
        )
        val uri = Uri.parse("content://kwbooktrack.test/export.tc")
        val sink = ByteArrayOutputStream()
        Shadows.shadowOf(context.contentResolver).registerOutputStream(uri, sink)

        vm.exportTellicoToUri(uri)
        val state = vm.state.first { !it.inProgress }

        assertThat(state.error).isNull()
        assertThat(state.lastManifest).isNotNull()
        val names = ZipInputStream(ByteArrayInputStream(sink.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertThat(names).contains("tellico.xml")
    }
}
