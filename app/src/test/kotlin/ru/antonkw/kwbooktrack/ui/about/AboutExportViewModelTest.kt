package ru.antonkw.kwbooktrack.ui.about

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.exporter.ExportZipUseCase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AboutExportViewModelTest {
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
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        val bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            NoOpCoverStorage,
            photoRepository,
        )
        useCase = ExportZipUseCase(
            bookRepository = bookRepository,
            noteDao = db.noteDao(),
            sessionDao = db.readingSessionDao(),
            photoDao = db.photoDao(),
            tagDao = db.tagDao(),
            versionProvider = versionProvider,
            database = db,
        )
        vm = AboutExportViewModel(
            context = ApplicationProvider.getApplicationContext(),
            exportZipUseCase = useCase,
        )
    }

    @After
    fun tearDown() {
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
}
