package ru.antonkw.kwbooktrack.ui.add

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
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.repository.BookRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddBookViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun saveInsertsBookWithManualSource() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext())
        vm.setTitle("Dune")
        vm.setAuthor("Frank Herbert")
        vm.setIsbn("9780441172719")
        vm.setPageCount("500")
        vm.save()
        advanceUntilIdle()

        val books = db.bookDao().observeAll().first()
        assertThat(books).hasSize(1)
        val book = books.first()
        assertThat(book.title).isEqualTo("Dune")
        assertThat(book.author).isEqualTo("Frank Herbert")
        assertThat(book.isbn13).isEqualTo("9780441172719")
        assertThat(book.pageCount).isEqualTo(500)
        assertThat(book.source).isEqualTo(BookSource.MANUAL)
    }

    @Test
    fun emptyTitleDoesNotSave() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext())
        vm.save()
        advanceUntilIdle()

        val books = db.bookDao().observeAll().first()
        assertThat(books).isEmpty()
    }

    @Test
    fun setIsbnStripsNonDigits() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext())
        vm.setIsbn("978-0-441-17271-X")
        assertThat(vm.state.value.isbn).isEqualTo("978044117271X")
    }

    @Test
    fun setPageCountStripsNonDigits() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext())
        vm.setPageCount("5x0x0")
        assertThat(vm.state.value.pageCount).isEqualTo("500")
    }

    @Test
    fun shortIsbnIsKeptAsIs() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext())
        vm.setIsbn("12345")
        vm.setTitle("Test")
        vm.save()
        advanceUntilIdle()

        val books = db.bookDao().observeAll().first()
        assertThat(books.first().isbn13).isNull() // only 13-digit ISBNs are stored
    }
}
