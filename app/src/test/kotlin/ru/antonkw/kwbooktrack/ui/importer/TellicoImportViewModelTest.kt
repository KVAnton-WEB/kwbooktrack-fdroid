package ru.antonkw.kwbooktrack.ui.importer

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
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.TestViewModels
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.importer.ImportKwBooktrackZipUseCase
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoUseCase
import ru.antonkw.kwbooktrack.data.importer.ImportTellicoZipUseCase
import ru.antonkw.kwbooktrack.data.importer.TellicoBookMapper
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val applier = SnapshotApplier(
            bookRepository = bookRepository,
            noteRepository = noteRepository,
            sessionRepository = SessionRepository(db.readingSessionDao()),
            tagRepository = TagRepository(db.tagDao()),
            photoRepository = photoRepository,
            photoStorage = NoOpPhotoStorage,
            transcoder = CoverTranscoder(),
        )
        useCase = ImportTellicoZipUseCase(
            importUseCase = ImportTellicoUseCase(TellicoCsvReader(), TellicoBookMapper(), applier),
            csvReader = TellicoCsvReader(),
            xmlReader = TellicoXmlReader(),
        )
        vm = TellicoImportViewModel(
            ApplicationProvider.getApplicationContext(),
            useCase,
            ImportKwBooktrackZipUseCase(applier),
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
    fun initialStateIsIdleWithNoResultNoError() {
        val s = vm.state.value
        assertThat(s.inProgress).isFalse()
        assertThat(s.result).isNull()
        assertThat(s.error).isNull()
    }

    @Test
    fun resetClearsEverything() = runTest(dispatcher) {
        vm.reset()
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
        val s = vm.state.first { !it.inProgress }
        assertThat(s.inProgress).isFalse()
        assertThat(s.result).isNull()
        assertThat(s.error).isNotNull()
    }

    @Test
    fun tellicoArchiveIsImportedAsTellico() = runTest(dispatcher) {
        val file = zip("books.tc", "tellico.xml" to TELLICO_XML)

        vm.importFromUri(Uri.fromFile(file))
        val state = vm.state.first { !it.inProgress }

        assertThat(state.error).isNull()
        assertThat(state.result).isNotNull()
        assertThat(state.result!!.imported).isEqualTo(1)
        assertThat(db.bookDao().observeAll().first().single().title).isEqualTo("Дюна")
    }

    @Test
    fun ownBackupArchiveIsImportedAsBackup() = runTest(dispatcher) {
        val file = zip(
            "backup.zip",
            "manifest.json" to MANIFEST,
            "books.csv" to "id;title;author\n1;Дюна;Фрэнк Герберт\n",
        )

        vm.importFromUri(Uri.fromFile(file))
        val state = vm.state.first { !it.inProgress }

        assertThat(state.error).isNull()
        assertThat(state.result).isNotNull()
        assertThat(state.result!!.imported).isEqualTo(1)
        assertThat(db.bookDao().observeAll().first().single().title).isEqualTo("Дюна")
    }

    @Test
    fun foreignArchiveLandsOnErrorState() = runTest(dispatcher) {
        // Ни tellico.xml, ни manifest.json — формат не определить.
        val file = zip("other.zip", "readme.txt" to "ничего тут нет")

        vm.importFromUri(Uri.fromFile(file))
        val state = vm.state.first { !it.inProgress }

        assertThat(state.error).isNotNull()
        assertThat(state.result).isNull()
    }

    @Test
    fun fileWithoutRecordsLandsOnErrorState() = runTest(dispatcher) {
        // Текст без заголовка CSV: записей ноль — «успешного» импорта не было.
        val file = File.createTempFile("notes", ".txt").apply { writeText("просто текст") }

        vm.importFromUri(Uri.fromFile(file))
        val state = vm.state.first { !it.inProgress }

        assertThat(state.error).isNotNull()
        assertThat(state.result).isNull()
    }

    @Test
    fun failedImportClearsResultOfPreviousRun() = runTest(dispatcher) {
        vm.importFromUri(Uri.fromFile(zip("books.tc", "tellico.xml" to TELLICO_XML)))
        assertThat(vm.state.first { !it.inProgress }.result).isNotNull()

        vm.importFromUri(Uri.fromFile(zip("other.zip", "readme.txt" to "ничего тут нет")))
        val state = vm.state.first { !it.inProgress }

        // Счётчики прошлого прогона не должны читаться как текущие.
        assertThat(state.error).isNotNull()
        assertThat(state.result).isNull()
    }

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val file = File.createTempFile(name, ".tmp")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private companion object {
        private const val MANIFEST = """
            {"schemaVersion":1,"appVersion":"0.5.51","exportedAt":0,"source":"kwbooktrack",
             "counts":{"books":1,"sessions":0,"notes":0,"quotes":0,"photos":0,"tags":0}}
        """

        private val TELLICO_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://www.tellico.org/" date="2024-01-01">
              <collection type="2">
                <fields>
                  <field name="title" title="Заголовок" type="1"/>
                  <field name="author" title="Автор" type="1"/>
                </fields>
                <entry id="1">
                  <title>Дюна</title>
                  <authors>
                    <author>Фрэнк Герберт</author>
                  </authors>
                </entry>
              </collection>
            </tellico>
        """.trimIndent()
    }
}
