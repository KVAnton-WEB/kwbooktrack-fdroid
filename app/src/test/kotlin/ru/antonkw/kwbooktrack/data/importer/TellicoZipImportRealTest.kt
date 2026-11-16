package ru.antonkw.kwbooktrack.data.importer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class TellicoZipImportRealTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: BookRepository
    private lateinit var useCase: ImportTellicoZipUseCase
    private lateinit var tempOut: File
    private lateinit var zipFile: File

    @Before
    fun setUp() {
        val resource = javaClass.classLoader!!.getResource("tellico.zip")
            ?: error("tellico.zip not found in test resources")
        zipFile = File(resource.toURI())
        assumeTrue("tellico.zip present", zipFile.exists() && zipFile.length() > 0)

        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        repo = BookRepository(
            db.bookDao(),
            db.tagDao(),
            NoOpCoverStorage,
            photoRepository,
        )
        useCase = ImportTellicoZipUseCase(
            importUseCase = ImportTellicoUseCase(
                csvReader = TellicoCsvReader(),
                mapper = TellicoBookMapper(),
                applier = SnapshotApplier(
                    bookRepository = repo,
                    noteRepository = NoteRepository(db.noteDao(), NoOpPhotoStorage),
                    sessionRepository = SessionRepository(db.readingSessionDao()),
                    tagRepository = TagRepository(db.tagDao()),
                    photoRepository = photoRepository,
                    photoStorage = NoOpPhotoStorage,
                    transcoder = CoverTranscoder(),
                ),
            ),
            csvReader = TellicoCsvReader(),
            xmlReader = TellicoXmlReader(),
        )
        tempOut = Files.createTempDirectory("kwbooktrack-zip-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempOut.deleteRecursively()
    }

    @Test
    fun importsBooksWithCoversFromRealZip() = runBlocking {
        // В тестовом архиве лежит tellico/tellico.csv, а не tellico.xml.
        val result = useCase(zipFile, ImportFormat.TELLICO_CSV, tempOut)
        assertThat(result.total).isGreaterThan(500)
        assertThat(result.imported).isGreaterThan(500)
        assertThat(result.coversCopied).isGreaterThan(0)

        val books = db.bookDao().observeAll().first()
        val withCover = books.count { !it.coverPath.isNullOrBlank() }
        assertThat(withCover).isGreaterThan(50)

        // Sample several books that claim to have a cover and verify the file actually exists on disk.
        val sample = books.filter { !it.coverPath.isNullOrBlank() }.take(20)
        val missing = sample.count { book ->
            val f = File(book.coverPath!!)
            !f.exists() || f.length() == 0L
        }
        assertThat(missing).isEqualTo(0)
    }

    @Test
    fun importsQuotesAndReviewsFromRealZip() = runBlocking {
        val result = useCase(zipFile, ImportFormat.TELLICO_CSV, tempOut)
        assertThat(result.notesImported).isGreaterThan(0)

        val notes = db.noteDao().observeByKind(NoteKind.QUOTE)
            .first() + db.noteDao()
            .observeByKind(NoteKind.REVIEW)
            .first()
        assertThat(notes).isNotEmpty()

        val quotes = notes.filter { it.kind == NoteKind.QUOTE }
        val reviews = notes.filter { it.kind == NoteKind.REVIEW }
        assertThat(quotes).isNotEmpty()
        assertThat(reviews).isNotEmpty()
        // Связь с книгой должна быть установлена
        val orphanNotes = notes.count { db.bookDao().findById(it.mediaId) == null }
        assertThat(orphanNotes).isEqualTo(0)
    }
}
