package ru.antonkw.kwbooktrack.ui.importer

import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoZipUseCase
import ru.antonkw.kwbooktrack.data.importer.TellicoBookMapper
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TellicoImportViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var useCase: ImportTellicoZipUseCase
    private lateinit var vm: TellicoImportViewModel
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            NoOpCoverStorage,
            photoRepository,
        )
        noteRepository = NoteRepository(db.noteDao(), NoOpPhotoStorage)
        useCase = ImportTellicoZipUseCase(
            TellicoCsvReader(),
            TellicoXmlReader(),
            TellicoBookMapper(),
            SnapshotApplier(
                bookRepository = bookRepository,
                noteRepository = noteRepository,
                sessionRepository = SessionRepository(db.readingSessionDao()),
                tagRepository = TagRepository(db.tagDao()),
                photoRepository = photoRepository,
                photoStorage = NoOpPhotoStorage,
                transcoder = CoverTranscoder(),
            ),
        )
        vm = TellicoImportViewModel(ApplicationProvider.getApplicationContext(), useCase)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateIsIdleWithNoResultNoError() {
        val s = vm.state.value
        assertThat(s.inProgress).isFalse()
        assertThat(s.result).isNull()
        assertThat(s.error).isNull()
    }

    @Test
    fun resetClearsEverything() = runTest(dispatcher) {
        vm.reset()
        advanceUntilIdle()
        assertThat(vm.state.value.inProgress).isFalse()
        assertThat(vm.state.value.result).isNull()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun unreadableUriLandsOnErrorState() = runTest(dispatcher) {
        // A bogus content:// URI returns null from openInputStream, which the
        // VM maps to tellico_import_error_read.
        val bogus = Uri.parse("content://kwbooktrack.test/no-such-zip")
        vm.importFromUri(bogus)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.inProgress).isFalse()
        assertThat(s.result).isNull()
        assertThat(s.error).isNotNull()
    }
}