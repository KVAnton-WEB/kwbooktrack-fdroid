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

    private val bookmoryDir = File("/home/kvanton/projects/pet/kwbooktrack/Bookmory")

    @Before
    fun setUp() {
        assumeTrue("real Bookmory data present", bookmoryDir.exists() && bookmoryDir.listFiles()?.isNotEmpty() == true)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        sessionRepository = SessionRepository(db.readingSessionDao())
        noteRepository = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage)
        useCase = ImportBookmoryUseCase(BookmoryParser(), bookRepository, sessionRepository, noteRepository)
        tempDir = Files.createTempDirectory("kwbooktrack-bookmory-").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
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
        val result = useCase(bookmoryDir)

        assertThat(result.totalFiles).isGreaterThan(10)
        assertThat(result.importedBooks).isGreaterThan(10)
        assertThat(result.importedNotes).isGreaterThan(0)
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
}
