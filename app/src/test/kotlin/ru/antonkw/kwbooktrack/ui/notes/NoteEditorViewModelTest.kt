package ru.antonkw.kwbooktrack.ui.notes

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

    private fun newVm(bookId: Long = 1L, noteId: Long = 0L) = NoteEditorViewModel(
        androidx.lifecycle.SavedStateHandle(
            mapOf("bookId" to bookId, "noteId" to noteId).filterValues { it != 0L || it == bookId },
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
                bookId = bookId,
                page = page,
                bodyMd = body,
                kind = NoteKind.NOTE,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

    private suspend fun insertPhoto(bookId: Long, noteId: Long, path: String): Long =
        photoRepository.insert(
            PhotoEntity(bookId = bookId, noteId = noteId, path = path, createdAt = 0L),
        )

    @Test
    fun saveAsNotePersists() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.setPage("100")
        vm.setBody("Отличная глава.")
        vm.save(NoteKind.NOTE)
        advanceUntilIdle()

        val notes = db.noteDao().observeByBook(bookId).first()
        assertThat(notes).hasSize(1)
        assertThat(notes.first().page).isEqualTo(100)
        assertThat(notes.first().bodyMd).isEqualTo("Отличная глава.")
        assertThat(notes.first().kind).isEqualTo(NoteKind.NOTE)
        assertThat(notes.first().bookId).isEqualTo(bookId)
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
    fun emptyBodyIsNotSaved() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.save(NoteKind.NOTE)
        advanceUntilIdle()

        assertThat(db.noteDao().observeByBook(bookId).first()).isEmpty()
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
        assertThat(db.noteDao().observeByBook(bookId).first()).isEmpty()
        assertThat(vm.state.value.saved).isFalse()
    }

    @Test
    fun saveAsQuoteWithoutBodyDoesNotInsert() = runTest(dispatcher) {
        val bookId = insertBook()
        val vm = newVm(bookId)
        vm.save(NoteKind.QUOTE)
        advanceUntilIdle()
        assertThat(db.noteDao().observeByBook(bookId).first()).isEmpty()
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
