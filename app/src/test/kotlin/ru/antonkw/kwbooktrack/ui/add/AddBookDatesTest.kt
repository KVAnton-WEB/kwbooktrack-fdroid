package ru.antonkw.kwbooktrack.ui.add

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate

/**
 * Редактор книги: несколько дат прочтения.
 *
 * Книгу можно перечитать — даты прочтения за это и нужны.
 */
@RunWith(RobolectricTestRunner::class)
class AddBookDatesTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var books: BookRepository
    private lateinit var mediaDates: MediaDateRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        val photos = PhotoRepository(db.photoDao(), NoOpPhotoStorage)
        books = BookRepository(db.bookDao(), db.tagDao(), NoOpCoverStorage, photos)
        mediaDates = MediaDateRepository(db.mediaDateDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun readDatesAreSavedWithTheEarliestAsPrimary() = runTest(dispatcher) {
        val vm = newVm()
        vm.setTitle("Дюна")
        vm.setIsRead(true)
        vm.setFinishedDate(0, "10.05.2024")
        vm.addFinishedDate()
        vm.setFinishedDate(1, "01.02.2023")
        vm.save()?.join()

        val book = books.findById(books.observeAll().first().single().id)!!
        assertThat(book.finishedAt).isEqualTo(LocalDate.of(2023, 2, 1).toEpochMs())
        assertThat(mediaDates.getFor(book.id, MediaType.BOOKS))
            .containsExactly(LocalDate.of(2024, 5, 10).toEpochMs())
    }

    @Test
    fun untickedBookSavesNoDates() = runTest(dispatcher) {
        val vm = newVm()
        vm.setTitle("Дюна")
        vm.setIsRead(true)
        vm.setFinishedDate(0, "10.05.2024")
        vm.setIsRead(false)
        vm.save()?.join()

        val book = books.observeAll().first().single()
        assertThat(book.finishedAt).isNull()
        assertThat(mediaDates.getFor(book.id, MediaType.BOOKS)).isEmpty()
    }

    private fun newVm() = AddBookViewModel(
        SavedStateHandle(),
        books,
        mediaDates,
        ApplicationProvider.getApplicationContext(),
        NoOpCoverStorage,
    )
}
