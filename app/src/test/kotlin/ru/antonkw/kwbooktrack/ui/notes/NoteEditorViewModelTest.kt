package ru.antonkw.kwbooktrack.ui.notes

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import java.io.ByteArrayInputStream
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteEditorViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var noteRepository: NoteRepository
    private lateinit var photoRepository: PhotoRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        photoRepository = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage, photoRepository)
        noteRepository = NoteRepository(db.noteDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newVm(
        bookId: Long = 1L,
        noteId: Long = 0L,
        photoRepository: PhotoRepository = this.photoRepository,
    ) = NoteEditorViewModel(
        androidx.lifecycle.SavedStateHandle(
            mapOf("mediaId" to bookId, "noteId" to noteId).filterValues { it != 0L || it == bookId },
        ),
        noteRepository,
        photoRepository,
        ApplicationProvider.getApplicationContext(),
    )

    private suspend fun insertBook(): Long = bookRepository.insert(
        BookEntity(title = "Dune", author = "Herbert", createdAt = 0L, updatedAt = 0L),
    )

    private suspend fun insertNote(bookId: Long, body: String, page: Int? = null): Long =
        noteRepository.insert(
            NoteEntity(
                mediaId = bookId, mediaType = MediaType.BOOKS,
                page = page,
                bodyMd = body,
                kind = NoteKind.NOTE,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

    private suspend fun insertPhoto(bookId: Long, noteId: Long, path: String): Long =
        photoRepository.insert(
            PhotoEntity(mediaId = bookId, mediaType = MediaType.BOOKS, noteId = noteId, path = path, createdAt = 0L),
        )

    /** Подменённый поток картинки: ContentResolver отдаст его редактору. */
    private fun pickedImage(name: String): Uri = Uri.parse("content://test/$name").also {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>().contentResolver)
            .registerInputStream(it, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun photosAreSavedThroughPhotoStorage() = runTest(dispatcher) {
        // Файл фото пишется через PhotoStorage: там имя с UUID. Собственное
        // имя из миллисекунд у двух фото, прикреплённых в одну миллисекунду,
        // совпало бы — второе перезаписало бы первое.
        val bookId = insertBook()
        val vm = newVm(bookId, photoRepository = PhotoRepository(db.photoDao(), CountingPhotoStorage))
        vm.setBody("десять фото")
        repeat(10) { index -> vm.attachPhoto(pickedImage("photo$index.jpg")) }
        vm.save()
        vm.state.first { it.saved }

        val photos = db.photoDao().observeByMedia(bookId, MediaType.BOOKS).first()
        assertThat(photos).hasSize(10)
        // Путь пришёл из хранилища (а не собран вручную) и у каждого фото свой.
        assertThat(photos.all { it.path.startsWith("/photos/stub_") }).isTrue()
        assertThat(photos.map { it.path }.distinct()).hasSize(10)
    }

    /** Хранилище-заглушка: уникальный путь на каждый вызов. */
    private object CountingPhotoStorage : ru.antonkw.kwbooktrack.data.storage.PhotoStorage {
        private val counter = java.util.concurrent.atomic.AtomicInteger()

        override fun saveForNote(noteId: Long, source: InputStream, extension: String): String =
            "/photos/stub_${counter.incrementAndGet()}.$extension"

        override fun saveNamed(name: String, source: InputStream, extension: String): String =
            "/photos/$name.$extension"

        override fun deleteForNote(noteId: Long) = Unit

        override fun deletePaths(paths: Collection<String>) = Unit
    }

    @Test
    fun saveAsNotePersists() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.setPage("100")
        vm.setBody("Отличная глава.")
        vm.save(NoteKind.NOTE)
        advanceUntilIdle()

        val notes = db.noteDao().observeByMedia(bookId, MediaType.BOOKS).first()
        assertThat(notes).hasSize(1)
        assertThat(notes.first().page).isEqualTo(100)
        assertThat(notes.first().bodyMd).isEqualTo("Отличная глава.")
        assertThat(notes.first().kind).isEqualTo(NoteKind.NOTE)
        assertThat(notes.first().mediaId).isEqualTo(bookId)
    }

    @Test
    fun saveAsQuotePersists() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.setBody("Кто не умеет в час нужды расстаться со своими сокровищами, тот несвободен.")
        vm.save(NoteKind.QUOTE)
        advanceUntilIdle()

        // The insert inside save() suspends on Room's executor — a real thread
        // the test scheduler can't advance — so advanceUntilIdle() above can
        // return before the row is written. Wait for it instead of racing it.
        val quotes = db.noteDao().observeByKind(NoteKind.QUOTE).first { it.isNotEmpty() }
        assertThat(quotes).hasSize(1)
        assertThat(quotes.first().kind).isEqualTo(NoteKind.QUOTE)
    }

    @Test
    fun editingNoteKeepsItsPhotosAttached() = runTest(dispatcher) {
        // Правка заметки идёт через insert с REPLACE, а это удаление строки
        // плюс вставка: внешний ключ photos.note_id (ON DELETE SET NULL)
        // обнулял бы привязку всех её фото.
        val bookId = insertBook()
        val noteId = insertNote(bookId, "мысль")
        db.photoDao().insert(
            PhotoEntity(
                mediaId = bookId,
                mediaType = MediaType.BOOKS,
                noteId = noteId,
                path = "/files/photos/p.jpg",
                createdAt = 0L,
            ),
        )

        val vm = newVm(bookId = bookId, noteId = noteId)
        advanceUntilIdle()
        vm.setBody("мысль переписана")
        vm.save()
        // Запись идёт на потоке Room — реальном, который планировщик теста не
        // двигает, поэтому advanceUntilIdle() возвращается раньше записи.
        // Ждём состояния saved: оно выставляется после того, как запись
        // прошла.
        vm.state.first { it.saved }

        val photos = db.photoDao().observeByNote(noteId).first()
        assertThat(photos).hasSize(1)
        assertThat(photos.single().noteId).isEqualTo(noteId)
        assertThat(db.noteDao().findById(noteId)!!.bodyMd).isEqualTo("мысль переписана")
    }

    @Test
    fun reviewRouteSavesNoteAsReview() = runTest(dispatcher) {
        // «Написать рецензию» открывает редактор с kind=REVIEW в маршруте.
        // Без этого save() сохранял бы заметку: kind неявно был NOTE, и
        // рецензию было не сохранить вовсе.
        val bookId = insertBook()
        val vm = NoteEditorViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("mediaId" to bookId, "kind" to "REVIEW")),
            noteRepository,
            photoRepository,
            ApplicationProvider.getApplicationContext(),
        )
        assertThat(vm.initialKind).isEqualTo(NoteKind.REVIEW)
        vm.setBody("Отличная книга.")
        vm.save()
        advanceUntilIdle()

        val reviews = db.noteDao().observeByKind(NoteKind.REVIEW).first { it.isNotEmpty() }
        assertThat(reviews).hasSize(1)
        assertThat(reviews.first().bodyMd).isEqualTo("Отличная книга.")
        assertThat(reviews.first().mediaId).isEqualTo(bookId)
    }

    @Test
    fun emptyBodyIsNotSaved() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.save(NoteKind.NOTE)
        advanceUntilIdle()

        assertThat(db.noteDao().observeByMedia(bookId, MediaType.BOOKS).first()).isEmpty()
    }

    @Test
    fun setPageOnlyAcceptsDigits() = runTest(dispatcher) {
        val vm = newVm()
        vm.setPage("1a2b3c")
        assertThat(vm.state.value.pageText).isEqualTo("123")
    }

    @Test
    fun attachPhotoAppendsToPendingList() = runTest(dispatcher) {
        val vm = newVm()
        val u1 = Uri.parse("content://media/1")
        val u2 = Uri.parse("content://media/2")
        vm.attachPhoto(u1)
        vm.attachPhoto(u2)
        assertThat(vm.state.value.pendingPhotos).containsExactly(u1, u2).inOrder()
    }

    @Test
    fun removePendingPhotoDropsUri() = runTest(dispatcher) {
        val vm = newVm()
        val u1 = Uri.parse("content://media/1")
        val u2 = Uri.parse("content://media/2")
        vm.attachPhoto(u1)
        vm.attachPhoto(u2)
        vm.removePendingPhoto(u1)
        assertThat(vm.state.value.pendingPhotos).containsExactly(u2)
    }

    @Test
    fun canSaveAsNoteTrueWhenPendingPhotosEvenIfBodyBlank() = runTest(dispatcher) {
        val vm = newVm()
        assertThat(vm.state.value.canSaveAsNote).isFalse()
        vm.attachPhoto(Uri.parse("content://media/x"))
        assertThat(vm.state.value.canSaveAsNote).isTrue()
    }

    @Test
    fun canSaveAsQuoteRequiresNonBlankBody() = runTest(dispatcher) {
        val vm = newVm()
        assertThat(vm.state.value.canSaveAsQuote).isFalse()
        vm.attachPhoto(Uri.parse("content://media/x"))
        assertThat(vm.state.value.canSaveAsQuote).isFalse()
        vm.setBody("quote")
        assertThat(vm.state.value.canSaveAsQuote).isTrue()
    }

    @Test
    fun saveWithoutBodyOrPhotosDoesNotInsert() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.save(NoteKind.NOTE)
        advanceUntilIdle()
        assertThat(db.noteDao().observeByMedia(bookId, MediaType.BOOKS).first()).isEmpty()
        assertThat(vm.state.value.saved).isFalse()
    }

    @Test
    fun saveAsQuoteWithoutBodyDoesNotInsert() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.save(NoteKind.QUOTE)
        advanceUntilIdle()
        assertThat(db.noteDao().observeByMedia(bookId, MediaType.BOOKS).first()).isEmpty()
    }

    @Test
    fun markExistingPhotoForDeletionUpdatesTotalPhotos() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.markExistingPhotoForDeletion(99L)
        assertThat(vm.state.value.photoIdsToDelete).contains(99L)
        assertThat(vm.state.value.totalPhotos).isEqualTo(-1)

        vm.unmarkExistingPhotoForDeletion(99L)
        assertThat(vm.state.value.photoIdsToDelete).isEmpty()
    }
}
