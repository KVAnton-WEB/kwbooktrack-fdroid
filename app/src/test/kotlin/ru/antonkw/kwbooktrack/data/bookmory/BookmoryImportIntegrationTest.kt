package ru.antonkw.kwbooktrack.data.bookmory

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
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class BookmoryImportIntegrationTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var useCase: ImportBookmoryUseCase
    private lateinit var tempDir: File
    private lateinit var bookmoryDir: File
    private val createdDirs = mutableListOf<File>()

    @Before
    fun setUp() {
        // Prefer the bundled sample; fall back to KWBOOKTRACK_BOOKMORY_DIR env var
        // for the real ~hundreds-of-files dataset.
        val resource = javaClass.classLoader!!.getResource("fixtures/bookmory/sample-book.md")
        bookmoryDir = if (resource != null) {
            val tmp = Files.createTempDirectory("kwbooktrack-bookmory-fixture-").toFile()
            File(resource.toURI()).copyTo(File(tmp, "sample-book.md"))
            tmp
        } else {
            val envDir = System.getenv("KWBOOKTRACK_BOOKMORY_DIR")
            if (envDir != null) File(envDir) else File("Bookmory")
        }
        assumeTrue("bookmory data present", bookmoryDir.exists() && bookmoryDir.listFiles()?.isNotEmpty() == true)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
        sessionRepository = SessionRepository(db.readingSessionDao())
        noteRepository = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage)
        useCase = ImportBookmoryUseCase(BookmoryParser(), bookRepository, sessionRepository, noteRepository)
        tempDir = Files.createTempDirectory("kwbooktrack-bookmory-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
        createdDirs.forEach { it.deleteRecursively() }
        if (bookmoryDir.parentFile?.name?.startsWith("kwbooktrack-bookmory-fixture-") == true) {
            bookmoryDir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun parsesSampleMarkdown() {
        val book = BookmoryParser().parse(
            """
            ***
            ### Информация о книге
            - Название: Норвежский лес
            - Авторы: Харуки Мураками
            - Переводчики:
            - Издательство: Эксмо
            - Дата публикации: 2026
            - Язык: ru
            - ISBN: 9785040944675
            - Тип книги: Мягкая обложка
            - Всего страниц: с. 368
            - Используемые теги: #Художественнаялитература #Зарубежнаялитература
            - Статус: Дочитано!

            ***
            ### Журнал чтения 1
            - Период чтения: 08.04.2026 ~ 30.06.2026
            - Оценка: 4.0

            ***
            ### Список заметок
            #### #1
            - Дата: 15.04.2026 12:00
            - Страница: с. 100
            - Тип заметки: Цитата
            - Содержание: Какой-то текст цитаты.
            """.trimIndent(),
        )!!

        assertThat(book.title).isEqualTo("Норвежский лес")
        assertThat(book.authors).containsExactly("Харуки Мураками")
        assertThat(book.publisher).isEqualTo("Эксмо")
        assertThat(book.publicationDate).isEqualTo("2026")
        assertThat(book.isbn).isEqualTo("9785040944675")
        assertThat(book.totalPages).isEqualTo(368)
        assertThat(book.tags).containsExactly("Художественнаялитература", "Зарубежнаялитература").inOrder()
        assertThat(book.status).isEqualTo(BookmoryStatus.FINISHED)
        assertThat(book.sessions).hasSize(1)
        assertThat(book.sessions.first().rating).isEqualTo(4.0f)
        assertThat(book.notes).hasSize(1)
        assertThat(book.notes.first().page).isEqualTo(100)
    }

    @Test
    fun importsRealBookmoryDirectory() = runBlocking {
        // Real Bookmory directory has hundreds of files; the bundled sample has 1.
        // Use the file count to gate the bulk assertion.
        val result = useCase(bookmoryDir)
        val isRealData = bookmoryDir.listFiles()!!.size > 5

        if (isRealData) {
            assertThat(result.totalFiles).isGreaterThan(10)
            assertThat(result.importedBooks).isGreaterThan(10)
            assertThat(result.importedNotes).isGreaterThan(0)
        } else {
            assertThat(result.totalFiles).isEqualTo(1)
            assertThat(result.importedBooks).isEqualTo(1)
            assertThat(result.importedNotes).isEqualTo(1)
        }
        assertThat(result.skipped).isEqualTo(0)

        // Verify books exist in DB
        val books = db.bookDao().observeAll().first()
        assertThat(books).hasSize(result.importedBooks)

        // Verify notes are persisted
        val notes = db.noteDao().observeByKind(NoteKind.NOTE).first()
        assertThat(notes.size).isAtLeast(result.importedNotes)
    }

    @Test
    fun emptyDirectoryImportsNothing() = runBlocking {
        val result = useCase(tempDir)
        assertThat(result.totalFiles).isEqualTo(0)
        assertThat(result.importedBooks).isEqualTo(0)
    }

    @Test
    fun sessionEndPageComesFromLatestNoteInRange() = runBlocking {
        // Session 08.04–30.06 contains notes on 15.04 page 100 and 20.05 page 230.
        // endPage must be 230 (the latest page reached), not the earlier note.
        val dir = newIsolatedDir("end-page-test")
        File(dir, "book.md").writeText(
            """
            ***
            ### Информация о книге
            - Название: Тестовая книга
            - Авторы: Тест
            - Всего страниц: с. 368
            - Статус: Дочитано!

            ***
            ### Журнал чтения 1
            - Период чтения: 08.04.2026 ~ 30.06.2026

            ***
            ### Список заметок
            #### #1
            - Дата: 15.04.2026 12:00
            - Страница: с. 100
            - Тип заметки: Цитата
            - Содержание: ранняя заметка.
            #### #2
            - Дата: 20.05.2026 12:00
            - Страница: с. 230
            - Тип заметки: Цитата
            - Содержание: поздняя заметка.
            """.trimIndent(),
        )
        useCase(dir)
        val sessions = db.readingSessionDao().observeAll().first()
        assertThat(sessions).hasSize(1)
        assertThat(sessions.single().endPage).isEqualTo(230)
    }

    @Test
    fun finishedSessionWithoutNotesFallsBackToPageCount() = runBlocking {
        // Finished book with no notes → last FINISHED session gets pageCount
        // (368) so pages can be reconstructed end-to-end.
        val dir = newIsolatedDir("no-notes-test")
        File(dir, "book.md").writeText(
            """
            ***
            ### Информация о книге
            - Название: Без заметок
            - Авторы: Тест
            - Всего страниц: с. 368
            - Статус: Дочитано!

            ***
            ### Журнал чтения 1
            - Период чтения: 01.01.2026 ~ 31.01.2026
            """.trimIndent(),
        )
        useCase(dir)
        val sessions = db.readingSessionDao().observeAll().first()
        assertThat(sessions.single().endPage).isEqualTo(368)
    }

    @Test
    fun nonLastSessionWithoutNotesKeepsEndPageNull() = runBlocking {
        // Reading book, only the last session gets the pageCount fallback;
        // a prior session with no notes stays null.
        val dir = newIsolatedDir("non-last-test")
        File(dir, "book.md").writeText(
            """
            ***
            ### Информация о книге
            - Название: В процессе
            - Авторы: Тест
            - Всего страниц: с. 500
            - Статус: Читаю!

            ***
            ### Журнал чтения 1
            - Период чтения: 01.01.2026 ~ 15.01.2026
            ### Журнал чтения 2
            - Период чтения: 16.01.2026 ~ 31.01.2026
            """.trimIndent(),
        )
        useCase(dir)
        val sessions = db.readingSessionDao().observeAll().first()
            .sortedBy { it.startedAt }
        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].endPage).isNull()
        assertThat(sessions[1].endPage).isNull()
    }

    private fun newIsolatedDir(name: String): File {
        val dir = Files.createTempDirectory("kwbooktrack-$name-").toFile()
        createdDirs += dir
        return dir
    }
}
